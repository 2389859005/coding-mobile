const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const {webcrypto} = require('node:crypto');
(async () => {
  let sends=0;
  const api={getAll:async()=>[{sessionId:'s',title:'Task',secret:'omit'}],
    getSupportedCommands:async options=>{assert.ok(options && typeof options==='object' && !Array.isArray(options));return options;},
    getTranscript:async()=>[{type:'assistant',message:{role:'assistant',content:[{type:'text',text:'正在检查布局'}]}}],
    sendMessage:async()=>{sends++;return true;}};
  const window={'claude.web':{LocalSessions:api,LocalAgentModeSessions:api}};
  const config={transport:'cdp',token:'test-secret',generation:'test-generation'};
  const code=fs.readFileSync('claude_bridge/desktop-file.js','utf8').replace('__BRIDGE_CONFIG__',JSON.stringify(config));
  await vm.runInNewContext(code,{window,crypto:webcrypto,TextEncoder,console,setTimeout,clearTimeout,Date});
  const bridge=window.__claudeMobileBridge;
  assert.equal(bridge.status().transport,'cdp'); // No readFileAtCwd/start/write APIs exist.
  let seq=0;const request=(method,args=[],extra={})=>({token:config.token,generation:config.generation,seq:++seq,expires:Date.now()/1000+30,surface:'code',method,args,...extra});
  const rows=await bridge.invoke(request('getAll'));assert.equal(rows.result[0].secret,undefined);
  for(const surface of ['code','cowork'])for(const args of [[],['s'],[{sessionId:'s',cwd:'D:/fixture'}]]){
    const result=await bridge.invoke(request('getSupportedCommands',args,{surface}));assert.equal(result.error,undefined);
    assert.equal(result.result.sessionId,args.length?'s':undefined);
  }
  const progress=await bridge.invoke(request('mobileProgress',['s']));assert.equal(progress.result[0].message.content[0].text,'正在检查布局');
  const message=request('sendMessage',['s','next']);await bridge.invoke(message);assert.equal(sends,1);
  assert.ok((await bridge.invoke(message)).error);assert.equal(sends,1);
  assert.ok((await bridge.invoke(request('sendMessage',[],{token:'wrong'}))).error);
  assert.ok((await bridge.invoke(request('sendMessage',[],{expires:0}))).error);
  assert.ok((await bridge.invoke(request('arbitrary'))).error);assert.equal(sends,1);
  bridge.stop();assert.ok((await bridge.invoke(request('getAll'))).error);
  console.log('PASS: direct CDP without project/file APIs; sanitized session/progress; expiry, authentication, allowlist, replay protection.');
})().catch(e=>{console.error(e);process.exitCode=1;});
