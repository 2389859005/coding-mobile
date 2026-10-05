// Harness plugin: authenticated loopback adapter over the installed session services.
import {createServer} from 'node:http';
import {readFile,writeFile,rename,unlink} from 'node:fs/promises';
import {timingSafeEqual,randomUUID} from 'node:crypto';
export const inject=['sessionController','sessionSkillCatalog','permissionPresets','agents','workspaceRegistry'];
export async function apply(ctx,config){
  const {token,endpoint}=JSON.parse(await readFile(config.configPath,'utf8'));
  const questions=new Map(),history=new Map();
  const signal=()=>new AbortController().signal;
  const list=async()=> (await ctx.sessionController.list({},signal())).items;
  const find=async id=>{const row=(await list()).find(r=>r.sessionId===id);if(!row)throw Error('DeepSeek 会话不存在');return row;};
  const values=row=>row.projections?.values||{};
  const normalize=row=>{const v=values(row),m=v.modelSelection?.next||v.modelSelection?.lastUsed||{};return {id:row.sessionId,title:v.title||'新会话',cwd:row.cwd||'',backend:'deepseek',status:row.running?'active':'idle',updatedAt:row.updatedAt,model:m.provider&&m.model?m.provider+'/'+m.model:'',effort:m.reasoningEffort||'',requests:[...questions.values()].filter(q=>q.sid===row.sessionId).map(q=>q.public)};};
  const records=async row=>{
    const projection=await ctx.sessionController.projections({sessionId:row.sessionId},signal());
    const seq=projection.asOfSeq??row.projections?.asOfSeq??0;
    const old=history.get(row.sessionId);if(old?.seq===seq)return old.records;
    let before,all=[],last=old?.records.at(-1)?.event.seq??-1;
    for(let page=0;page<100;page++){
      const got=await ctx.sessionController.page({address:{kind:'session',sessionId:row.sessionId},throughSeq:seq,...before===undefined?{}:{beforeSeq:before},maxMessages:150},signal());
      const batch=got.records||[];all.unshift(...batch.filter(r=>r.event.seq>last));
      if(!got.hasMore||!batch.length||batch[0].event.seq<=last)break;
      before=batch[0].event.seq;
    }
    all=[...(old?.records||[]),...all];history.set(row.sessionId,{seq,records:all});if(history.size>8)history.delete(history.keys().next().value);return all;
  };
  const bounded=async promise=>{let timer;try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('插件目录读取超时，请重试')),6000);})]);}finally{clearTimeout(timer);}};
  const localized=value=>typeof value==='string'?value:value?.['zh-CN']||value?.zh||value?.en||'';
  const catalog=async(sid,section='all')=>{
    const row=normalize(await find(sid)),result={currentModel:row.model,currentEffort:row.effort};
    if(section!=='plugins'){
      const raw=await bounded(ctx.sessionController.modelCatalog());
      result.models=raw.groups.flatMap(g=>g.models.map(m=>({id:g.id+'/'+m.id,name:m.name,efforts:(m.reasoning?.efforts||[]).map(e=>e.id),defaultEffort:m.reasoning?.defaultEffort})));
    }
    if(section!=='models'){
      const skills=await bounded(ctx.sessionSkillCatalog.list({sessionId:sid},signal()));
      result.skills=skills.skills.map(s=>({id:s.name,name:s.name,kind:'skill',description:s.description,provider:s.provider,selectable:s.invocation?.userInvocable!==false}));
      const inventory=ctx.get?.('pluginInventory')||ctx.pluginInventory;
      if(inventory){
        try{
          const snapshot=await bounded(inventory.list()),packages=new Map();
          for(const entry of [...(snapshot.entries||[]),...(snapshot.agentPresets||[]).flatMap(p=>p.rows.map(r=>({...r,preset:p.name||p.id})))]){
            const key=entry.moduleName;if(!key)continue;
            const status=entry.fiberPhase==='active'?'运行中':entry.fiberPhase==='failed'?'加载失败':entry.enabled===false?'已停用':'按会话启用';
            const old=packages.get(key);if(old?.status==='运行中')continue;
            packages.set(key,{id:'installed:'+key,name:localized(entry.meta?.title)||key.replace('@deepseek-ai/dsh-',''),description:localized(entry.meta?.description),kind:'plugin',selectable:false,status});
          }
          result.skills.push(...[...packages.values()].sort((a,b)=>a.name.localeCompare(b.name)));
        }catch(error){result.pluginError=String(error.message||error);}
      }else result.pluginError='当前 Harness 未提供插件清单，请更新或重新打开 Harness';
    }
    return result;
  };
  // Both Desktop and phone remain answerers; the first explicit answer wins.
  ctx.on('user-questions/request',async(req,next)=>{
    if(!req.agent||Object.isFrozen(req))return next();
    const id=randomUUID(),controller=new AbortController(),original=req.signal;
    req.signal=original?AbortSignal.any([original,controller.signal]):controller.signal;
    let resolveMobile,rejectMobile;
    const mobile=new Promise((resolve,reject)=>{resolveMobile=resolve;rejectMobile=reject;});
    questions.set(id,{sid:req.agent.id,public:{id,tool:'提问',needsInput:true,input:{questions:req.questions}},resolve:resolveMobile});
    const abort=()=>rejectMobile(Error('提问已取消'));original?.addEventListener('abort',abort,{once:true});
    try{return await Promise.race([Promise.resolve().then(next),mobile]);}
    finally{questions.delete(id);controller.abort();original?.removeEventListener('abort',abort);req.signal=original;}
  },{prepend:true,global:true});
  async function dispatch(action,sid,body){
    if(action==='list')return {connected:true,sessions:(await list()).filter(r=>!r.parentSessionId).map(r=>({...normalize(r),archived:(ctx.workspaceRegistry.archivedSessionIds||[]).includes(r.sessionId)})).sort((a,b)=>Number(a.archived)-Number(b.archived))};
    if(action==='projects'){const seen=new Map();for(const row of await list())if(row.cwd)seen.set(row.cwd,{key:row.cwd,cwd:row.cwd,name:row.cwd.replaceAll('\\','/').split('/').pop()});for(const w of ctx.workspaceRegistry.list())seen.set(w.path,{key:w.path,cwd:w.path,name:w.name||w.path});return {projects:[...seen.values()]};}
    if(action==='create'){const project=(await dispatch('projects')).projects.find(p=>p.key===body.projectKey);if(!project)throw Error('请选择现有项目');const result=await ctx.sessionController.create({sessionId:'session-'+body.id,cwd:project.cwd});if(body.title)await ctx.sessionController.rename({sessionId:result.sessionId,title:body.title});return {status:'ready',result:{id:result.sessionId,title:body.title||'新会话'}};}
    const row=await find(sid);
    if(action==='rename'){const title=String(body.title||'').trim();if(!title||title.length>120)throw Error('标题为 1–120 字');await ctx.sessionController.rename({sessionId:sid,title});return {status:'accepted'};}
    if(action==='archive'){if(body.confirmed!==true)throw Error('请确认归档');await ctx.workspaceRegistry.archiveSession(sid,{stopActivity:true});return {status:'accepted'};}
    if(action==='open')return {ok:true};
    if(action==='detail')return {session:normalize(row),records:(await records(row)).filter(r=>['user/message','assistant/message','tool/call','tool/result','todo/write','turn/start','turn/end'].includes(r.event.type)),steps:values(row).todos||[],connected:true};
    if(action==='catalog')return catalog(sid,body.section||'all');
    if(action==='access'){const options=ctx.permissionPresets.catalog().options;if(body.mode){if(!options.some(o=>o.value===body.mode))throw Error('权限选项无效');await ctx.sessionController.create({sessionId:sid,cwd:row.cwd});const agent=ctx.agents.get(sid);if(!agent)throw Error('当前会话没有活动智能体');ctx.permissionPresets.set(agent.session,body.mode);}return {status:'accepted',mode:body.mode||values(row).permissions?.currentValue,modes:options.map(o=>o.value),options};}
    if(action==='settings'){const c=await catalog(sid,'models'),selected=body.model||c.currentModel,m=c.models.find(m=>m.id===selected);if(!m)throw Error('请选择可用模型');const effort=body.effort??(selected===c.currentModel?c.currentEffort:m.defaultEffort);if(effort&&!m.efforts.includes(effort))throw Error('该模型不支持所选思考强度');const slash=selected.indexOf('/');await ctx.sessionController.selectModel({sessionId:sid,provider:selected.slice(0,slash),model:selected.slice(slash+1),reasoningEffort:effort||undefined});return {status:'accepted',model:selected,effort:effort||''};}
    if(action==='stop'){await ctx.sessionController.cancel({sessionId:sid});return {status:'accepted'};}
    if(action==='send'){let text=body.text||'';if(body.plugins?.length){const c=await catalog(sid,'plugins');if(body.plugins.some(id=>!c.skills.some(s=>s.id===id&&s.selectable!==false)))throw Error('插件已不可用');text='请使用以下已安装技能：'+body.plugins.join(', ')+'\n\n'+text;}const content=text?[{type:'text',text}]:[];for(const image of body.resolvedImages||[]){const [header,data]=image.url.split(',');content.push({type:'image',mediaType:header.slice(5).split(';')[0],data});}if(!content.length)throw Error('请输入内容');await ctx.sessionController.prompt({sessionId:sid,requestId:body.id,mode:body.mode==='steer'&&row.running?'steer':'queue',content},signal());return {status:row.running?'queued':'accepted'};}
    if(action==='respond'){const pending=questions.get(body.requestId);if(!pending||pending.sid!==sid)throw Error('提问已过期');const answers=pending.public.input.questions.map(q=>{const selected=body.answers?.[q.id]?.answers;if(!Array.isArray(selected)||!selected.length||selected.some(s=>typeof s!=='string'||s.length>20000))throw Error('请回答每一项问题');const labels=(q.options||[]).map(o=>o.label);return {id:q.id,selected:selected.filter(s=>labels.includes(s)),custom:selected.filter(s=>!labels.includes(s)).join('\n')};});pending.resolve({answers});return {status:'accepted'};}
    throw Error('不支持的 DeepSeek 操作');
  }
  const server=createServer(async(req,res)=>{try{
    const credential=String(req.headers.authorization||'').replace(/^Bearer /,'');const a=Buffer.from(credential),b=Buffer.from(token);
    if(req.method!=='POST'||req.url!=='/mobile'||req.headers.origin||a.length!==b.length||!timingSafeEqual(a,b)){res.writeHead(403);res.end();return;}
    let size=0,chunks=[];for await(const chunk of req){size+=chunk.length;if(size>5*1024*1024)throw Error('请求过大');chunks.push(chunk);}const {action,sid,body={}}=JSON.parse(Buffer.concat(chunks));
    const result=await dispatch(action,sid,body);res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(result));
  }catch(error){res.writeHead(400,{'content-type':'application/json'});res.end(JSON.stringify({error:String(error.message||error)}));}});
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  const port=server.address().port;await writeFile(endpoint+'.tmp',JSON.stringify({port,pid:process.pid}));await rename(endpoint+'.tmp',endpoint);
  ctx.on('dispose',async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await unlink(endpoint).catch(()=>{});});
}
