import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {apply} from '../coding_remote/deepseek-host.mjs';

const folder=await mkdtemp(join(tmpdir(),'mobile-harness-test-')),endpoint=join(folder,'endpoint.json'),configPath=join(folder,'config.json');
await writeFile(configPath,JSON.stringify({token:'fixture-secret',endpoint}));
const handlers=new Map(),calls=[];let pluginReads=0,modelReads=0;
const row={sessionId:'session-one',cwd:folder,running:true,updatedAt:1,projections:{asOfSeq:3,values:{title:'Fixture',todos:[{content:'check',status:'in_progress'}],modelSelection:{next:{provider:'deepseek',model:'chat',reasoningEffort:'high'}}}}};
const ctx={sessionController:{list:async()=>({items:[row]}),projections:async()=>({asOfSeq:3}),page:async()=>({records:[{event:{seq:1,type:'request/header',data:{token:'PRIVATE'}}},{event:{seq:2,type:'user/message',data:{content:[{type:'text',text:'hello'}]}}}],hasMore:false}),
  modelCatalog:async()=>{modelReads++;return {groups:[{id:'deepseek',models:[{id:'chat',name:'Chat',reasoning:{efforts:[{id:'high'}]}}]}]};},prompt:async value=>calls.push(value),cancel:async value=>calls.push(value)},
  pluginInventory:{list:async()=>{pluginReads++;return {entries:[{moduleName:'@deepseek-ai/dsh-skill-office',enabled:true,fiberPhase:'active',meta:{title:{'zh-CN':'Office 文档'}}}],agentPresets:[{id:'default',rows:[{moduleName:'@deepseek-ai/dsh-skill-office',enabled:true,fiberPhase:'active'},{moduleName:'@deepseek-ai/dsh-tool-browser',enabled:false,fiberPhase:null}]}]};}},
  sessionSkillCatalog:{list:async()=>({skills:[{name:'review',description:'Review'}]})},permissionPresets:{catalog:()=>({options:[]})},workspaceRegistry:{list:()=>[]},agents:{},on:(name,fn)=>handlers.set(name,fn)};
await apply(ctx,{configPath});const {port}=JSON.parse(await readFile(endpoint,'utf8'));
const request=async(action,body={},token='fixture-secret')=>{const response=await fetch(`http://127.0.0.1:${port}/mobile`,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify({action,sid:'session-one',body})});return response;};
try{
  assert.equal((await request('list',{},'wrong')).status,403);
  const detail=await(await request('detail')).json();assert.equal(detail.session.title,'Fixture');assert.ok(!JSON.stringify(detail).includes('PRIVATE'));
  const models=await(await request('catalog')).json();assert.equal(models.models[0].id,'deepseek/chat');
  assert.equal(models.skills.filter(p=>p.id==='installed:@deepseek-ai/dsh-skill-office').length,1);assert.equal(models.skills.find(p=>p.name==='Office 文档').selectable,false);
  const before=pluginReads;await request('catalog',{section:'models'});assert.equal(pluginReads,before);
  const modelBefore=modelReads;const plugins=await(await request('catalog',{section:'plugins'})).json();assert.equal(modelReads,modelBefore);assert.equal(plugins.models,undefined);
  assert.equal((await request('send',{id:'invalid',text:'no',plugins:['installed:@deepseek-ai/dsh-skill-office']})).status,400);assert.equal(calls.length,0);
  assert.equal((await(await request('send',{id:'r',text:'follow-up',plugins:['review']})).json()).status,'queued');assert.equal(calls[0].mode,'queue');assert.ok(calls[0].content[0].text.includes('review'));
  let desktopAborted=false;
  const req={agent:{id:'session-one'},questions:[{id:'q',question:'Choose',options:[{label:'A'}]}],signal:new AbortController().signal};
  const waiting=handlers.get('user-questions/request')(req,()=>new Promise((resolve,reject)=>req.signal.addEventListener('abort',()=>{desktopAborted=true;reject(Error('cancelled'));})));
  const question=(await(await request('list')).json()).sessions[0].requests[0];assert.equal(question.input.questions[0].id,'q');
  await request('respond',{requestId:question.id,answers:{q:{answers:['A','custom']}}});
  const answer=await waiting;assert.deepEqual(answer.answers[0],{id:'q',selected:['A'],custom:'custom'});assert.ok(desktopAborted);
  assert.equal((await request('unsupported')).status,400);
  console.log('PASS: Harness authenticated session adapter, code-free reads, model catalog, queued prompt, synchronized question and stale-dialog cancellation.');
}finally{await handlers.get('dispose')();await rm(folder,{recursive:true,force:true});}
