import fs from 'node:fs/promises';
import {Presentation,PresentationFile,FileBlob} from 'file:///C:/Users/User/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs';
const src='C:/Users/User/OneDrive/文件/카카오톡 받은 파일/MCP와 서브태스크 템플릿을 활용한 LLM 기반 시뮬레이션 서비스 생성.pptx';
const p=await PresentationFile.importPptx(await FileBlob.load(src));
await fs.writeFile('source.proto.json',JSON.stringify(p.toProto()));
await fs.writeFile('source.inspect.txt',(await p.inspect({kind:'slide,shape,layout',maxChars:40000})).ndjson);
for(let i=0;i<p.slides.items.length;i++) {
 const b=await p.export({slide:p.slides.items[i],format:'png',scale:0.7});
 await fs.writeFile(`source-${i+1}.png`,new Uint8Array(await b.arrayBuffer()));
}
console.log('Source slides rendered:',p.slides.items.length);
console.log(p.help('slides',{include:['index','notes'],maxChars:3000}));

