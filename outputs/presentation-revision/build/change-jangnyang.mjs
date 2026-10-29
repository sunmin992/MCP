import fs from 'node:fs/promises';
let src=await fs.readFile('build-registration.mjs','utf8');
const start=src.indexOf("s=slide('6. 실제 명세 파일'");
const end=src.indexOf("s=slide('6. 요청 처리 흐름'",start);
let part=src.slice(start,end);
for(const [a,b] of [
 ['주차 서버는 지원 범위를 능력 카드 JSON으로 보관한다','장량동 서버의 지원 범위를 능력 카드에 정의한다'],
 ['parking-capability-card.json','jangnyang-capability-card.json'],
 ['pohang-parking-sim','jangnyang-waste-sim'],
 ['localhost:8092','localhost:8090'],
 ['public-parking','waste-collection'],
 ['"scope": "area"','"scope": "block"'],
 ["scope:'area'","scope:'block'"],
 ['pohang-parking-ses','jangnyang-ses'],
 ['"lots": {"min": 1, "max": 12, "default": 4}','"buildings": {"min": 1, "max": 26, "default": 4}'],
 ['lots:{min:1,max:12,default:4}','buildings:{min:1,max:26,default:4}'],
 ['공영주차장을 대상으로 하며\\n주차장 1~12곳을 지원한다고 선언한다.','원룸촌 한 블록을 대상으로 하며\\n건물 1~26동을 지원한다고 선언한다.'],
 ['서버 시작 시 명세 원문을 HTTP로 등록한다','장량동 서버가 시작되면 능력 카드 원문을 등록한다'],
 ['CapabilityCardLoader가 능력 카드 JSON 적재','장량동 서버가 jangnyang-capability-card.json 적재'],
 ['CandidateRegistry가 serverId별 관리, registry.json에 저장','jangnyang-waste-sim으로 목록에 보관하고 파일에 저장'],
 ['상세 템플릿은 각 서버에 두고, 서버 선택 후 get_templates로 제공한다.','장량동 템플릿은 서버의 ses/jangnyang-templates.json에 보관한다.'],
 ['parking-sim/src/main/resources/mcp/','waste-sim-spring/simulator/src/main/resources/mcp/'],
 ['parking-sim/src/main/java/com/parkingsim/','waste-sim-spring/simulator/src/main/java/com/wastesim/'],
 ]) part=part.replaceAll(a,b);
src=src.slice(0,start)+part+src.slice(end);
src=src.replaceAll('시뮬레이션_서비스_명세등록_추가본.pptx','시뮬레이션_서비스_장량동_명세등록.pptx').replace('validation-registration.json','validation-jangnyang.json');
await fs.writeFile('build-jangnyang.mjs',src);
