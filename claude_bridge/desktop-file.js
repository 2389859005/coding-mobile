/* Injected into the Claude renderer through the verified DevTools Console. Uses the existing
 * workspace file APIs and session APIs; never changes CSP or workspace trust.
 * Runtime files must remain local. Reloading the renderer removes this bridge.
 */
window.__claudeMobileBridge?.stop();
(async () => {
  const config = __BRIDGE_CONFIG__;
  const cdp = typeof __FILE_ONLY__ === 'undefined' && config.transport === 'cdp';
  const api = window['claude.web'];
  async function read() {
    const file = await api.LocalSessions.readFileAtCwd(config.cwd, config.request);
    return JSON.parse(file.contents);
  }
  const surfaces = {code: 'LocalSessions', cowork: 'LocalAgentModeSessions'};
  const capabilities = Object.fromEntries(Object.entries(surfaces).map(([kind, name]) =>
    [kind, Object.keys(api[name] || {}).filter(method => typeof api[name][method] === 'function')]));
  // Read model choices already offered by Desktop. Do not call its model-list setter.
  const offeredModels = [];
  let knownSessions = [];
  const bounded = (promise, ms) => new Promise((resolve, reject) => {
    const timer=setTimeout(()=>reject(1),ms);
    Promise.resolve(promise).then(resolve,reject).finally(()=>clearTimeout(timer));
  });
  const restore = [];
  // Desktop streams these events before adding a complete assistant message to
  // getTranscript(). Keep only public text; reasoning deltas never leave Desktop.
  const streams = new Map();
  function observeStream(surface, event) {
    const id=event?.sessionId;
    if (!id) return;
    const key=surface+':'+id, row=event.message;
    if (['turn_withdrawn','session_deleted','session_cleared'].includes(event.type)) {streams.delete(key);return;}
    if (!row || row.parent_tool_use_id) return;
    if (['user','result'].includes(row.type)) {streams.delete(key);return;}
    if (row.type==='assistant') {
      if (!streams.get(key)?.id || row.message?.id===streams.get(key).id) streams.delete(key);
      return;
    }
    if (row.type!=='stream_event') return;
    const delta=row.event;
    if (delta?.type==='message_start') {
      streams.set(key,{id:delta.message?.id || row.uuid,blocks:new Map()});
      if(streams.size>100)streams.delete(streams.keys().next().value);
    }
    // Injection/reconnection can happen halfway through a response, after its
    // message_start event. Preserve subsequent visible text until the final row.
    if (!streams.has(key) && delta?.type==='content_block_delta' && delta.delta?.type==='text_delta')
      streams.set(key,{id:'',blocks:new Map()});
    const current=streams.get(key);
    if (!current) return;
    if (delta?.type==='content_block_start' && delta.content_block?.type==='text')
      current.blocks.set(delta.index,delta.content_block.text || '');
    if (delta?.type==='content_block_delta' && delta.delta?.type==='text_delta') {
      const text=(current.blocks.get(delta.index)||'')+delta.delta.text;
      current.blocks.set(delta.index,text.slice(0,500000));
    }
  }
  function transcriptWithStream(target,id,rows) {
    const surface=Object.values(surfaces).find(name=>api[name]===target);
    const current=streams.get(surface+':'+id);
    const result=sanitize('getTranscript',rows);
    if (!current || !Array.isArray(result)) return result;
    if (result.some(row=>row.message?.id===current.id)) return result;
    const text=[...current.blocks.entries()].sort((a,b)=>a[0]-b[0]).map(pair=>pair[1]).join('\n\n');
    const streamId=current.id || 'mobile-stream:'+surface+':'+id;
    return text ? [...result,{type:'assistant',uuid:streamId,message:{id:streamId,role:'assistant',content:[{type:'text',text}]}}] : result;
  }
  for (const surface of Object.values(surfaces)) {
    const target=api[surface];
    if (typeof target?.onEvent==='function') {
      try {const undo=target.onEvent(event=>observeStream(surface,event));if(typeof undo==='function')restore.push(undo);} catch {}
    }
  }
  for (const [kind, surface] of Object.entries(surfaces)) {
    capabilities[kind].push('mobileCatalog', 'mobileProgress', 'mobileSession');
    const target = api[surface], original = target?.setAvailableCodeModels;
    if (typeof original === 'function') {
      const observer = function (models, ...rest) {
        if (Array.isArray(models)) offeredModels.splice(0, offeredModels.length, ...models);
        return original.call(target, models, ...rest);
      };
      try { target.setAvailableCodeModels = observer; restore.push(() => { if (target.setAvailableCodeModels === observer) target.setAvailableCodeModels = original; }); } catch {}
    }
  }
  async function mobileCatalog(target) {
    const result = await bounded(target.getAll(),2500).catch(()=>knownSessions);
    const rows = Array.isArray(result)?result:Array.isArray(result?.sessions)?result.sessions:knownSessions;
    const choices = [...offeredModels, ...rows.map(r => r.model).filter(Boolean)];
    const models = new Map();
    for (const raw of choices) {
      const id = typeof raw === 'string' ? raw : raw.id || raw.value || raw.model;
      if (typeof id !== 'string' || !id) continue;
      const name = typeof raw === 'string' ? id : raw.displayName || raw.label || raw.name || id;
      models.set(id, {id, name, efforts: typeof target.setEffort === 'function' ? ['low','medium','high','max'] : []});
    }
    return {models: [...models.values()]};
  }
  // Persist only fields used by the phone, not provider configuration or tokens.
  function publicSession(row) {
    if (!row || typeof row !== 'object') return row;
    return Object.fromEntries('sessionId title cwd originCwd model lastActivityAt createdAt isArchived isRunning turnRunning lifecycleState stopOwed error pendingToolPermissions permissionMode effort effortLevel'.split(' ').filter(k => k in row).map(k => [k, row[k]]));
  }
  function sanitize(method, result) {
    if (method === 'getContextUsageSummary') return result && typeof result === 'object'
      ? Object.fromEntries('usedTokens totalTokens total_tokens contextWindow maxTokens max_tokens'.split(' ')
          .filter(k => typeof result[k] === 'number' && Number.isFinite(result[k])).map(k => [k, result[k]])) : null;
    if (method === 'getAll' && Array.isArray(result)) return result.map(publicSession);
    if (method === 'getSession') return publicSession(result);
    if (method === 'getTranscript' && Array.isArray(result)) return result
      .filter(row => ['user', 'assistant'].includes(row.type || row.message?.role))
      .map(row => {
        const source = row.message || row;
        let content = typeof source.content === 'string' ? source.content :
          (Array.isArray(source.content) ? source.content : []).filter(b =>
            'text input_text output_text tool_use tool_result image input_image thinking_summary'.split(' ').includes(b.type))
          .map(b => b.type === 'tool_use' ? {type:b.type,name:b.name,input:b.input,id:b.id} :
            b.type === 'tool_result' ? {type:b.type,content:b.content,tool_use_id:b.tool_use_id} :
            ['image','input_image'].includes(b.type) ? {type:b.type} : b.type === 'thinking_summary' ? {type:b.type,text:b.text || b.summary} : {type:b.type,text:b.text});
        const result = row.toolUseResult;
        if (result && typeof result === 'object') {
          const detail = ['stdout','stderr','output','content'].filter(k => typeof result[k] === 'string' && result[k]).map(k => result[k]).join('\n');
          if (detail) {
            if (typeof content === 'string') content = [{type:'text',text:content}];
            const tool = content.find(b => b.type === 'tool_result');
            if (tool && (!tool.content || Array.isArray(tool.content) && !tool.content.length)) tool.content = detail;
            else if (!tool) content.push({type:'tool_result',content:detail});
          }
        }
        return {type:row.type,uuid:row.uuid,isSynthetic:row.isSynthetic === true,isMeta:row.isMeta === true,
          message:{id:source.id,role:source.role || row.type,content}};
      });
    return result ?? null;
  }
  function callArgs(method, args) {
    if (method !== 'getSupportedCommands') return args;
    const options = args[0];
    // Older assistants sent a bare session ID (or no argument).
    return [typeof options === 'string' ? {sessionId:options} :
      options && typeof options === 'object' && !Array.isArray(options) ? options : {}];
  }
  async function mobileSession(target, id) {
    // One mailbox round trip, with metadata read after the transcript so a
    // completed turn cannot be paired with its earlier running flag.
    const transcript = await target.getTranscript(id);
    const session = await target.getSession(id);
    const usage = typeof target.getContextUsageSummary === 'function'
      ? await bounded(target.getContextUsageSummary(id), 1500).catch(()=>null) : null;
    return {session:publicSession(session), transcript:transcriptWithStream(target,id,transcript),
      contextUsage:sanitize('getContextUsageSummary',usage)};
  }
  if (cdp) {
    const allowed = new Set('getAll getSession getTranscript sendMessage interrupt stop respondToToolPermission start getPermissionMode archive delete updateSession rewindV2 setPermissionMode setModel setEffort getDefaultEffort getSupportedCommands setThinkingSummariesWanted getContextUsageSummary listSessionDirectory readSessionFile readSessionImageAsDataUrl readSessionPanelDocumentAsDataUrl mobileCatalog mobileProgress mobileSession'.split(' '));
    for (const kind in capabilities) capabilities[kind] = capabilities[kind].filter(method => allowed.has(method));
    let stopped = false, highWater = 0;
    window.__claudeMobileBridge = {
      stop() { stopped = true; for (const undo of restore) undo(); },
      status() { return {transport:'cdp', stopped, surfaces:capabilities}; },
      async invoke(msg) {
        if (stopped || msg?.token !== config.token || msg.generation !== config.generation)
          return {error:'连接已过期'};
        if (!Number.isSafeInteger(msg.seq) || msg.seq <= highWater) return {error:'请求已处理，不会重复执行'};
        highWater = msg.seq;
        if (typeof msg.expires !== 'number' || msg.expires < Date.now()/1000) return {error:'请求已过期'};
        try {
          const target = api[surfaces[msg.surface]];
          if (!target || !Array.isArray(msg.args)) throw new Error('不支持的桌面操作');
          if (msg.method === 'mobileCatalog') return {result:await mobileCatalog(target)};
          if (msg.method === 'mobileSession') return {result:await mobileSession(target,msg.args[0])};
          if (msg.method === 'mobileProgress') { const rows=await target.getTranscript(msg.args[0]); return {result:transcriptWithStream(target,msg.args[0],Array.isArray(rows)?rows.slice(-24):[])}; }
          if (!allowed.has(msg.method)) throw new Error('不支持的桌面操作');
          const result=await target[msg.method](...callArgs(msg.method,msg.args));
          if(msg.method==='rewindV2')streams.delete(surfaces[msg.surface]+':'+msg.args[0]);
          return {result:sanitize(msg.method,result)};
        } catch (error) { return {error:String(error.message || error)}; }
      }
    };
    return;
  }
  const initial = await bounded(read(),4000).catch(()=>{throw Error('无法读取桥接目录，请检查目录访问权限');});
  const normalize = path => String(path || '').replaceAll('\\', '/').replace(/\/$/, '').toLowerCase();
  const reusable = row => row?.sessionId && normalize(row.cwd) === normalize(config.cwd) && !row.isArchived;
  let bootstrapStopped=false;
  window.__claudeMobileBridge={stop(){bootstrapStopped=true;for(const undo of restore)undo();}};
  async function bridgeSession() {
    if (config.bridgeSessionId) {
      const cached=await bounded(api.LocalSessions.getSession(config.bridgeSessionId),2500).catch(()=>null);
      if(reusable(cached))return cached;
    }
    let found, failure, starting=false, probing=false;
    // Session registration precedes CLI/model initialization. A blank bridge
    // workspace only needs the file API; it does not need a model to be ready.
    const observe=event=>{
      const row=event?.session || event;
      if(reusable(row))found=row;
      else if(row?.sessionId)bounded(api.LocalSessions.getSession(row.sessionId),2500)
        .then(state=>{if(!bootstrapStopped&&reusable(state))found=state;}).catch(()=>{});
    };
    let undo;
    if(typeof api.LocalSessions.onEvent==='function')undo=api.LocalSessions.onEvent(observe);
    const start=()=>{
      if(found||starting||bootstrapStopped)return;
      starting=true;
      Promise.resolve().then(()=>api.LocalSessions.start({cwd:config.cwd,message:'',withoutFirstMessage:true,title:'桥接',useWorktree:false}))
        .then(async row=>{const state=reusable(row)?row:row?.sessionId?await bounded(api.LocalSessions.getSession(row.sessionId),2500):null;if(reusable(state))found=state;else throw Error('Claude 桥接目录与请求不一致');}).catch(error=>{failure=error;});
    };
    const probe=()=>{
      if(probing||bootstrapStopped)return;
      probing=true;
      Promise.resolve().then(()=>api.LocalSessions.getAll()).then(rows=>{
        knownSessions=Array.isArray(rows)?rows:[];
        found=knownSessions.find(reusable)||found;
        start();
      },()=>{start();}).finally(()=>{probing=false;});
    };
    // Inaccessible historical workspaces can leave getAll pending indefinitely.
    // Start the dedicated empty workspace once, without waiting for that scan.
    const startTimer=setTimeout(start,2500);
    const deadline=Date.now()+170000;
    try{
      while(!found&&!bootstrapStopped&&Date.now()<deadline){
        if(failure)throw failure;
        probe();await new Promise(resolve=>setTimeout(resolve,250));
      }
      if(!found)throw Error('Claude 会话目录尚未就绪，请检查目录信任');
      return found;
    }finally{clearTimeout(startTimer);if(typeof undo==='function')undo();}
  }
  let session;
  try{session=await bridgeSession();}catch(error){window.__claudeMobileBridge.stop();throw error;}
  if(bootstrapStopped)return;
  const sessionId = session.sessionId;
  let generation = initial.generation;
  const generations = new Set([generation]);
  let stopped = false, suspended = initial.type === 'stop', busy = false, highWater = initial.seq, lastWrite = 0, dirty = true;
  let state = {seq: 0, done: false};
  let eventRevision=0,publishTimer,publishing=null,publishWanted=false;
  function pushEvent(){
    if(stopped||suspended)return;
    eventRevision++;dirty=true;
    if(publishTimer)return;
    publishTimer=setTimeout(()=>{publishTimer=null;if(!stopped)publish().catch(()=>{dirty=true;});},30);
  }
  for(const surface of Object.values(surfaces)){
    const target=api[surface];
    // Desktop settings/cancel can finish without emitting a transcript event.
    // Observe completion while preserving the native result and calling it once.
    for(const name of ['setModel','setEffort','setPermissionMode','interrupt','stop','updateSession']){
      const original=target?.[name];if(typeof original!=='function')continue;
      const observer=function(...args){const result=original.apply(this,args);if(result&&typeof result.then==='function')result.then(pushEvent,()=>{});else pushEvent();return result;};
      try{target[name]=observer;restore.push(()=>{if(target[name]===observer)target[name]=original;});}catch{}
    }
    for(const name of ['onEvent','onToolPermissionRequest'])if(typeof target?.[name]==='function'){
      try{const undo=target[name](()=>pushEvent());if(typeof undo==='function')restore.push(undo);}catch{}
    }
  }
  restore.push(()=>clearTimeout(publishTimer));
  window.__claudeMobileBridge = {stop() { stopped = true; for (const undo of restore) undo(); }};
  function publish(){
    publishWanted=true;
    if(!publishing)publishing=(async()=>{do{publishWanted=false;await writePacket();}while(publishWanted&&!stopped);})().finally(()=>{publishing=null;});
    return publishing;
  }
  async function writePacket() {
    let value = {...state, generation, connected: !stopped && !suspended,
      timestamp: Date.now() / 1000, surfaces: capabilities,eventRevision,bridgeSessionId:sessionId};
    await api.LocalSessions.writeSessionFile(sessionId, config.response, JSON.stringify(value));
    lastWrite = Date.now();
  }
  try {
    // Publish readiness before another potentially slow request-file read.
    await bounded(publish(),4000);
    while (!stopped) {
      let msg;
      try { msg = await bounded(read(),4000); }
      catch (error) {
        if (!config.reconnect) throw error;
        await new Promise(resolve => setTimeout(resolve, 1000));
        continue;
      }
      if (msg && msg.generation !== generation) {
        // Only an idle packet starts a new generation; never replay a request.
        if (!config.reconnect || generations.has(msg.generation) || msg.type !== 'idle' || msg.seq !== 0) {
          await new Promise(resolve => setTimeout(resolve, 80)); continue;
        }
        generation = msg.generation; generations.add(generation);
        highWater = 0; state = {seq: 0, done: false}; busy = false; suspended = false; dirty = true;
      }
      if (msg?.type === 'stop') {
        if (!config.reconnect) break;
        if (!suspended) { suspended = true; dirty = true; }
      }
      if (!suspended && !busy && msg?.type === 'request' && msg.seq > highWater) {
        highWater = msg.seq;
        state = {seq: msg.seq, done: false};
        dirty = true;
        if (msg.expires < Date.now() / 1000) {
          state = {seq: msg.seq, done: true, error: 2};
        } else {
          busy = true;
          const dispatchedGeneration = generation;
          const operation = Promise.resolve().then(async () => {
            const target = api[surfaces[msg.surface]];
          if (target && msg.method === 'mobileCatalog') return mobileCatalog(target);
          if (target && msg.method === 'mobileSession') return mobileSession(target,msg.args[0]);
            if (target && msg.method === 'mobileProgress') { const rows=await target.getTranscript(msg.args[0]); return transcriptWithStream(target,msg.args[0],Array.isArray(rows)?rows.slice(-24):[]); }
            const result=await target[msg.method](...callArgs(msg.method,msg.args));
            if(msg.method==='rewindV2')streams.delete(surfaces[msg.surface]+':'+msg.args[0]);
            return sanitize(msg.method,result);
          });
          bounded(operation,Math.max(100,Math.min(90000,(msg.expires-Date.now()/1000)*1000))).then(result => { if (generation === dispatchedGeneration) state = {seq: msg.seq, done: true, result}; },
            error => { if (generation === dispatchedGeneration) state = {seq: msg.seq, done: true, error: String(error.message || error)}; })
            .finally(() => { if (generation === dispatchedGeneration) { busy = false; dirty = true; } });
        }
      }
      if (dirty || Date.now() - lastWrite > 5000) {
        dirty=false;
        try { await bounded(publish(),4000); }
        catch { dirty = true; }
      }
      await new Promise(resolve => setTimeout(resolve, 80));
    }
  } catch {}
  finally { stopped = true; for (const undo of restore) undo(); await publish().catch(() => {}); }
})();
