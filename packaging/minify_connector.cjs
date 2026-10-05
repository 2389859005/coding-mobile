// Build-only dependency, pinned in .local/minifier. Runtime needs no Node.js.
const fs=require('node:fs');
const {minify}=require('../.local/minifier/node_modules/terser');
(async()=>{
  const source=fs.readFileSync('claude_bridge/desktop-file.js','utf8');
  const result=await minify(source,{compress:true,mangle:true,format:{ascii_only:false,comments:false}});
  if(!result.code||result.code.includes('\n'))throw Error('Console script must be one line');
  fs.writeFileSync('claude_bridge/desktop-console.js','/* coding remote script */'+result.code,'utf8');
})().catch(error=>{console.error(error);process.exitCode=1;});
