import fs from 'node:fs/promises';
let script=await fs.readFile('build.mjs','utf8');
script=script.replace('p.slides.items.length<24','p.slides.items.length<26');
const extra=String.raw`
s=slide('6. 실제 명세 파일','주차 서버는 지원 범위를 능력 카드 JSON으로 보관한다',base);
text(s,'mcp/parking-capability-card.json의 일부',86,181,1108,35,23,C.blue,true);
const cardExcerpt={serverId:'pohang-parking-sim',endpoint:'http://localhost:8092/mcp',domain:['public-parking'],analysisUnit:{scope:'area'},sesDigest:{sesId:'pohang-parking-ses',sesVersion:'1.0.0'},applicability:{spatialScale:{lots:{min:1,max:12,default:4}}}};
text(s,JSON.stringify(cardExcerpt,null,2),86,229,580,350,20,C.navy);
text(s,'식별과 접속',715,237,465,37,26,C.blue,true);
text(s,'serverId로 서버를 구분하고\nendpoint로 MCP 호출 주소를 알린다.',715,280,465,75,23);
text(s,'분야와 규모',715,376,465,37,26,C.blue,true);
text(s,'공영주차장을 대상으로 하며\n주차장 1~12곳을 지원한다고 선언한다.',715,419,465,75,23);
text(s,'그 밖의 명세',715,511,465,37,26,C.blue,true);
text(s,'SES 요약, 조건부 지원, 미지원 기능,\n결과 지표와 자료의 가정도 함께 기록한다.',715,554,465,74,23);
s.speakerNotes.text+='\n코드 확인: parking-sim/src/main/resources/mcp/parking-capability-card.json. 화면의 JSON은 실제 필드를 발췌한 것으로 전체 등록 본문이 아니다. 서버 내부 CapabilityCardLoader는 리소스를 읽어 rawJson을 보존하며 get_capability와 등록에 같은 원문을 사용한다.';

s=slide('6. 브로커 등록','서버 시작 시 명세 원문을 HTTP로 등록한다',base);
table(s,[['순서','시뮬레이터와 브로커의 처리'],['1  명세 읽기','CapabilityCardLoader가 능력 카드 JSON 적재'],['2  등록 전송','BrokerRegistrar가 원문을 POST /api/simulators로 전송'],['3  등록 수신','브로커가 카드의 식별자·접속 정보 등을 검사'],['4  목록 보관','CandidateRegistry가 serverId별 관리, registry.json에 저장'],['5  요청에 활용','브로커는 등록된 명세를 요청과 대조해 후보·주소 반환']],[230,878],190,350,23);
text(s,'기본 등록 주소: http://127.0.0.1:8089/api/simulators',86,559,1108,35,24,C.blue,true);
text(s,'자동 등록은 서버 간 HTTP 호출이다. LLM이 호출하는 MCP 도구가 아니다.\n상세 템플릿은 각 서버에 두고, 서버 선택 후 get_templates로 제공한다.',86,612,1108,67,22);
s.speakerNotes.text+='\n근거: parking-sim/src/main/java/com/parkingsim/capability/BrokerRegistrar.java, waste-sim-spring/broker/src/main/java/com/wastesim/broker/SimulatorRegistrationController.java 및 CandidateRegistry.java. 같은 serverId 재등록은 갱신. 기본 저장 경로는 작업 디렉터리 기준 data/registry.json이며 설정으로 변경 가능. 등록 실패 시 기본 30초마다 재시도, 성공 후 반복 중단, 정상 종료 시 DELETE /api/simulators/{serverId} 시도. 등록 저장은 생존 여부의 보증이 아니다.';

`;
// Keep text-size and the existing reference typography; JSON needs a taller frame.
const fixed=extra.replace('JSON.stringify(cardExcerpt,null,2)',"JSON.stringify(cardExcerpt,null,2).replace(/\\n    /g,'\\n  ')").replace('580,350,20','580,398,18');
script=script.replace("s=slide('6. 요청 처리 흐름'",fixed+"s=slide('6. 요청 처리 흐름'");
script=script.replaceAll('시뮬레이션_서비스_설명_수정본_v3.pptx','시뮬레이션_서비스_명세등록_추가본.pptx').replace("'validation-v3.json'","'validation-registration.json'");
await fs.writeFile('build-registration.mjs',script);

