// Cold native startup and inaccessible historical folders must not hold the
// verified workspace file bridge behind model initialization or getAll().
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(process.env.CONNECTOR_TEMPLATE||'claude_bridge/desktop-file.js','utf8');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function scenario(kind){
  const config={cwd:'D:/bridge',request:'request.json',response:'response.json',reconnect:true};
  if(kind!=='cold')config.bridgeSessionId='local_cached';
  let response,starts=0,lists=0,registered=false;
  const listeners=new Set();
  const valid={sessionId:'local_bridge',cwd:config.cwd};
  const api={
    onEvent(fn){listeners.add(fn);return()=>listeners.delete(fn);},
    async readFileAtCwd(){return {contents:JSON.stringify({generation:'test',seq:0,type:'idle'})};},
    async getSession(id){return id==='local_bridge'?valid:{sessionId:id,cwd:kind==='cached'?config.cwd:'D:/other'};},
    async getAll(){lists++;assert.notEqual(kind,'cached','cached workspace must bypass global directory scan');if(kind==='blocked-list')return new Promise(()=>{});return registered?[valid]:[];},
    async start(options){
      starts++;assert.equal(starts,1);assert.equal(options.withoutFirstMessage,true);assert.equal(options.message,'');assert.equal(options.cwd,config.cwd);
      registered=true;
      if(kind==='blocked-list')for(const listener of listeners)listener({sessionId:valid.sessionId});
      // The native manager registers the session while start's CLI startup is
      // still pending. No completion of that promise is needed for the receipt.
      return new Promise(()=>{});
    },
    async writeSessionFile(id,path,text){assert.equal(id,kind==='cached'?'local_cached':'local_bridge');response=JSON.parse(text);return {hash:'ok'};}
  };
  const window={'claude.web':{LocalSessions:api,LocalAgentModeSessions:{}}};
  const running=vm.runInNewContext(source.replace('__BRIDGE_CONFIG__',JSON.stringify(config)),{window,console,clearTimeout,setTimeout:(fn,ms)=>setTimeout(fn,Math.min(ms,20))});
  try{
    const deadline=Date.now()+1500;
    while(!response?.connected&&Date.now()<deadline)await sleep(10);
    assert.equal(response?.connected,true,kind+' receives an early receipt');
    assert.equal(response.bridgeSessionId,kind==='cached'?'local_cached':'local_bridge');
    assert.equal(starts,kind==='cached'?0:1);
  }finally{window.__claudeMobileBridge?.stop();await running;}
  assert.equal(listeners.size,0);
}
(async()=>{for(const kind of ['cached','cold','stale','blocked-list'])await scenario(kind);console.log('PASS: cached workspace, blocked history scan, pending first start, stale workspace isolation, no duplicate start, listener cleanup');})().catch(error=>{console.error(error);process.exitCode=1;});
