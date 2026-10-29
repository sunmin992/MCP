import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {Presentation,PresentationFile} from 'file:///C:/Users/User/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs';
const ROOT='C:/Dev/MCP/outputs/presentation-revision';
const SKILL='C:/Users/User/.codex/plugins/cache/openai-primary-runtime/presentations/26.1025.11240/skills/presentations';
const PY='C:/Users/User/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe';
const SOURCE='C:/Dev/MCP/outputs/presentation-revision/deliverable/시뮬레이션_서비스_설명_수정본_v3.pptx';
const p=Presentation.load(JSON.parse(await fs.readFile('source.proto.json','utf8')));
const FONT='나눔고딕', C={navy:'#1C2B45',blue:'#2563A8',body:'#3D4A5C',gray:'#EDF1F6',bg:'#F7F8FA',orange:'#B4530F'};
const records=(await p.inspect({kind:'textbox,shape,image,table,chart',maxChars:1000000})).ndjson.split('\n').filter(Boolean).map(x=>JSON.parse(x));
p.delete(records.filter(r=>/^(sh|im|tb|ch)\//.test(r.id||'') && r.editable!==false).map(r=>r.id));
while(p.slides.items.length<26) p.slides.add();
let number=0; const tableOwners=[];
function text(s,txt,x,y,w,h,size=25,color=C.body,bold=false){
 const a=s.shapes.add({name:`text-${s.shapes.items.length}`,geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
 a.text=txt; a.text.style={typeface:FONT,fontSize:size,color,bold,verticalAlignment:'top'}; return a;
}
function slide(section,title,note=''){
 const s=p.slides.items[number++]; s.background.fill=C.bg;
 text(s,section,86,64,1050,26,18,C.blue,true);
 text(s,title,86,104,1110,68,36,C.navy,true);
 text(s,String(number).padStart(2,'0'),1135,672,60,24,16,C.body);
 s.speakerNotes.text=note;
 return s;
}
function foot(s,msg){text(s,msg,86,610,1108,48,22,C.blue,true)}
function para(s,label,body,x=86,y=196,w=510){text(s,label,x,y,w,42,27,C.blue,true);text(s,body,x,y+58,w,310,25);}
function rows(s,items){let y=192; for(const [label,desc] of items){text(s,label,86,y,240,70,25,C.blue,true);text(s,desc,350,y,835,76,25);y+=98;}}
function table(s,values,widths,top=190,height=372,size=23){
 const t=s.tables.add({rows:values.length,columns:values[0].length,left:86,top,width:1108,height,columnWidths:widths,values});
 for(let r=0;r<values.length;r++)for(let c=0;c<values[0].length;c++){
  const cell=t.getCell(r,c);cell.fill=r===0?C.navy:(r%2===0?C.gray:C.bg);
  cell.text.style={typeface:FONT,fontSize:size,color:r===0?'#FFFFFF':C.body,bold:r===0};
 }
 t.borders.assign({fill:'#D8DEE6',width:0.7,style:'solid'});tableOwners.push(number);return t;
}
const base='구현 근거: C:/Dev/MCP/parking-sim/src/main/java/com/parkingsim/ 및 waste-sim-spring/simulator/src/main/java/com/wastesim/. 자료 확인일 2026-10-28. 발표의 대화 사례는 설명용으로 구성했으며 실제 실행 결과를 의미하지 않는다.';
let s=slide('','',base);
text(s,'MCP와 서브태스크 템플릿을 활용한\nLLM 기반 시뮬레이션 서비스 생성 방법',86,240,1110,160,46,C.navy,true);
text(s,'기존 시뮬레이터의 연결과 검증된 실험 구성',86,430,1100,50,26,C.blue);
text(s,'30251271  이선민',86,630,700,36,20);

s=slide('1. 연구 배경','시뮬레이션 사용자와 제공자의 부담',base);
para(s,'사용자','모델의 구성과 입력 조건을 알아야 한다.\n\n목적에 맞는 매개변수를 정하고\n시뮬레이터의 실행 방법을 배워야 한다.');
para(s,'서비스 제공자','지원 기능과 제한을 사용자에게 설명해야 한다.\n\n시뮬레이터별 입력·검증·실행 방식을\n외부 서비스와 연결해야 한다.',670);
foot(s,'연구 목표: 모델별 지식을 명시하고 공통 대화·실행 절차를 재사용한다.');

s=slide('1. 문제 정의','자연어 요청과 실행 가능한 설정 사이의 간격',base);
para(s,'사용자의 요청','“한 블록에서 평일 교통을 반영해\n쓰레기를 몇 시에 수거해야\n모의 민원이 적은지 비교해줘.”');
para(s,'실행 전에 필요한 판단','이 규모와 분석 목적을 지원하는가?\n\n차량·주민·교통 조건 중 무엇이 빠졌는가?\n\n입력값과 값들의 조합이 유효한가?',670);
foot(s,'LLM은 요청을 해석하고, 서버는 명시된 규칙으로 조건을 판정한다.');

s=slide('2. 연구 목적','제공자와 사용자 관점의 연구 질문',base);
para(s,'RQ1  서비스 제공자','공통 MCP 도구 흐름과\n템플릿 초안 추출을 사용하면\n서비스 연결에 필요한 작업을\n줄일 수 있는가?');
para(s,'RQ2  사용자','모델 구조와 실행 방법을 몰라도\n자연어 대화로 필요한 조건을 정하고\n유효한 실험을 실행할 수 있는가?',670);
foot(s,'작업 감소와 사용성 향상은 별도 평가로 확인할 연구 질문이다.');

s=slide('2. 적용 범위','이 시스템이 구성하는 결과물',base);
rows(s,[['입력','사용자의 분석 목적·조건과 제공자가 정의한 모델 정보'],['처리','적합한 서버 탐색, 조건 확인, 응답 검증, 실행 설정 생성'],['결과물','사용자가 승인한 시나리오, 실행 결과, 조건과 한계의 설명'],['기존 엔진','모의 계산은 등록된 시뮬레이터 엔진이 수행한다.']]);
foot(s,'현재 구현 범위는 기존 엔진의 연결과 실험 구성이다.');

s=slide('3. MCP와 역할 분담','두 종류의 MCP 서버와 LLM의 역할',base);
table(s,[['주체','담당하는 일'],['LLM 앱','사용자 요청 해석, 추가 질문, 도구 호출, 결과 설명'],['브로커 MCP 서버','등록된 능력 카드와 요청을 비교하고 후보·접속 주소 반환'],['시뮬레이터 MCP 서버','템플릿 제공, 조건 판단, 설정 생성·검증, 승인 후 실행']],[300,808],190,340,24);
foot(s,'MCP는 도구의 이름·입력 형식·호출·응답을 연결하는 공통 방식이다.');
s.speakerNotes.text+='\nMCP 클라이언트가 tools/list와 tools/call을 사용한다. 두 종류라는 표현은 역할 구분이며 물리 서버 두 대를 뜻하지 않는다.';

s=slide('3. 모델 정보','능력 카드·SES·템플릿의 연결 관계',base);
table(s,[['정보','담는 내용','주요 사용처'],['능력 카드','지원 분야·규모·기능·제한·접속 주소','브로커의 서버 탐색'],['SES 정보','구성요소와 유형·개수·속성의 선택 구조','결정 항목의 의미와 위치'],['서브태스크 템플릿','질문 조건·허용값·기본값·설정 대응','조건 확인과 응답 검증'],['PES + 실험 프레임','확정한 공통 조건 + 비교할 변수와 값','실행 설정 생성']],[240,550,318],190,385,23);
foot(s,'브로커에는 능력 카드를 등록하고, 상세 템플릿은 선택된 서버에서 조회한다.');

s=slide('4. SES와 PES','SES는 모델의 구성과 선택 가능성을 표현한다',base);
table(s,[['표현','뜻','장량동 예'],['분해','한 요소를 하위 요소로 구성','차량·건물·교통·실험'],['특수화','여러 유형 중 선택','1톤·2.5톤·5톤'],['다중 분해','같은 요소의 개수 결정','차량 수, 건물 수'],['속성','요소의 값 결정','수거 시각, 배차 간격'],['결합 조건','영향을 포함할지 결정','교통 반영 여부']],[230,420,458],184,394,23);
foot(s,'현재 구현: 능력 카드의 SES 요약과 템플릿의 SES 경로·노드 종류로 표현한다.');
s.speakerNotes.text+='\n완전한 SES 트리를 범용 엔진이 읽어 시뮬레이터 코드를 자동 생성하는 구조로 설명하지 않는다.';

s=slide('4. SES와 PES','공통 구성과 비교할 실험 변수를 분리한다',base);
para(s,'PES: 선택한 공통 조건','차량 1톤 3대, 배차 간격 15분\n건물 4동, 동당 주민 25명\n교통 반영, 이동시간 방식 명시\n그 밖의 필수 조건과 출처 기록');
para(s,'실험 프레임: 비교 계획','비교 변수: 수거 시각\n비교값: 06시·09시·12시·15시·18시\n각 조건: 30일, 30회 반복\n관측값: 모의 민원·잔여량',670);
foot(s,'서버는 공통 조건에 비교값을 하나씩 적용하여 실행 설정 5벌을 만든다.');
s.speakerNotes.text+='\n예시 값은 설명용이다. SES 식별자와 버전, 응답값과 출처를 PES에 보존한다.';

s=slide('5. 서브태스크 템플릿','템플릿은 규칙이고, 서브태스크는 요청별 결정 작업이다',base);
para(s,'제공자가 준비하는 템플릿','결정 대상과 질문 초안\n질문이 필요한 조건\n값의 유형·선택지·범위\n기본값과 근거\nSES 경로와 실행 설정 필드');
para(s,'요청마다 정해지는 서브태스크','현재 조건에서 필요한가?\n이미 답한 값이 있는가?\n선행 조건을 먼저 확인해야 하는가?\n응답이 실행 설정에 어떻게 들어가는가?',670);
foot(s,'질문 순서는 고정하지 않고 현재 응답과 비교 조건에 따라 다시 판단한다.');

s=slide('5. 템플릿의 예','배차 간격 템플릿과 조건부 질문',base);
table(s,[['항목','배차 간격 템플릿의 내용'],['생성 조건','실제 운행 차량 수가 2대 이상'],['값과 제안값','정수 0~1,439분 / 제안값 0분'],['질문 초안','차량 간 배차 간격을 몇 분으로 할까요?'],['설정 대응','dispatchIntervalMinutes'],['SES 위치','수거차량집합/배차간격, 속성']],[260,848],185,350,23);
text(s,'1대: 질문 생성 안 함     3대: 배차 간격 확인',86,566,1100,44,27,C.blue,true);
text(s,'차량 수 미정: 필요 여부를 보류하고 차량 수부터 확인',86,621,1100,36,23);

s=slide('6. 제공자의 준비와 등록','능력 카드는 브로커에, 템플릿은 각 서버에 둔다',base);
rows(s,[['1  준비','능력 카드 JSON, 템플릿 JSON, 조건 평가·검증·실행 연결 코드'],['2  적재','서버가 시작할 때 카드와 템플릿을 읽어 보관'],['3  등록','능력 카드를 POST /api/simulators로 브로커에 전송'],['4  조회','서버 선택 후 get_templates로 상세 규칙 조회']]);
foot(s,'등록 실패 시 재시도한다. 등록 여부와 현재 호출 가능 여부는 구분한다.');
s.speakerNotes.text+='\n근거: parking-sim/src/main/java/com/parkingsim/capability/BrokerRegistrar.java, template/TemplateCatalog.java, waste-sim-spring/broker/src/main/java/com/wastesim/broker/CandidateRegistry.java. 기본 재시도 간격 30초, 성공 후 반복 전송 중단, 정상 종료 시 해제 시도. 저장 파일 설정 시 등록 영속화.';


s=slide('6. 실제 명세 파일','주차 서버는 지원 범위를 능력 카드 JSON으로 보관한다',base);
text(s,'mcp/parking-capability-card.json의 일부',86,181,1108,35,23,C.blue,true);
const cardExcerpt={serverId:'pohang-parking-sim',endpoint:'http://localhost:8092/mcp',domain:['public-parking'],analysisUnit:{scope:'area'},sesDigest:{sesId:'pohang-parking-ses',sesVersion:'1.0.0'},applicability:{spatialScale:{lots:{min:1,max:12,default:4}}}};
text(s,`{
  "serverId": "pohang-parking-sim",
  "endpoint": "http://localhost:8092/mcp",
  "domain": ["public-parking"],
  "analysisUnit": {"scope": "area"},
  "sesDigest": {
    "sesId": "pohang-parking-ses",
    "sesVersion": "1.0.0"
  },
  "applicability": {
    "spatialScale": {
      "lots": {"min": 1, "max": 12, "default": 4}
    }
  }
}`,86,229,595,400,21,C.navy);
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

s=slide('6. 요청 처리 흐름','탐색부터 사용자 승인까지의 공통 절차',base);
table(s,[['단계','LLM·사용자','서버'],['1  탐색','목적·규모·조건 전달','능력 카드와 대조해 후보 반환'],['2  구성','질문과 응답 반복','plan_subtasks로 필요한 항목 판단'],['3  검증','확정 조건과 비교 계획 전달','응답 검증, build_scenario로 설정 생성'],['4  승인','사용자가 확인 화면에서 승인','승인 상태와 실행 토큰 관리'],['5  실행','결과와 한계를 사용자에게 설명','토큰 확인 후 실행·결과 반환']],[180,455,473],188,395,23);
foot(s,'누락값이나 잘못된 조합이 있으면 실행 전에 보완한다.');

s=slide('7. 요청 예시: 탐색','사용자 목적을 서버가 지원하는 범위와 대조한다',base);
text(s,'“장량동 원룸 한 블록에서 평일 교통을 반영해\n수거 시각에 따른 모의 민원을 비교해줘.”',86,190,1108,100,29,C.navy,true);
table(s,[['요청에서 추출','능력 카드와 대조'],['쓰레기 배출·수거','장량동 서버의 분석 분야'],['원룸 한 블록','건물 1~26동의 지원 범위'],['평일 교통 반영','저장된 교통 프로파일 사용 가능'],['시각별 모의 민원 비교','수거 시각 비교와 모의 민원 지표 지원']],[440,668],320,265,23);
foot(s,'브로커는 후보와 근거를 반환한다. 세부 실행 가능성은 선택된 서버가 검사한다.');

s=slide('7. 요청 예시: 조건 확인','앞선 답변에 따라 다음 질문이 달라진다',base);
rows(s,[['사용자','“건물 4동, 동당 주민 25명, 1톤 차량 3대로 해줘.”'],['서버 판단','실제 운행 차량이 여러 대이므로 배차 간격 항목이 필요'],['LLM 질문','“차량은 몇 분 간격으로 출발할까요?”'],['사용자','“15분 간격으로 하고, 실험은 30일간 30회 반복해줘.”']]);
foot(s,'교통 프로파일·이동시간·경로 가용 적재량 등 남은 조건도 별도로 확인한다.');

s=slide('7. 요청 예시: 설정과 출처','사용자 응답과 모델의 제안값을 구분해 보여준다',base);
table(s,[['확인할 조건','예시 값','출처'],['차량·배차','1톤 3대, 15분 간격','사용자 응답'],['건물·주민','4동, 동당 25명','사용자 응답'],['기간·반복','30일, 30회','사용자 응답'],['이동시간 방식','상수 방식 또는 교통구역 근사','추가 확인 필요'],['차량별 경로 가용 적재량','블록에 배정한 용량','추가 확인 필요'],['주민 구성·배출량 등','모델이 제안한 값과 가정','제안 근거 표시 후 검토']],[340,470,298],184,400,22);
foot(s,'이 표는 구성 중인 예시다. 남은 필수값을 확정하기 전에는 실행하지 않는다.');

s=slide('7. 요청 예시: 승인과 결과','검토한 설정을 승인한 후 토큰으로 실행한다',base);
para(s,'실행 전','서버가 생성한 전체 설정 확인\n사용자 응답과 제안값의 출처 검토\n확인 화면에서 사용자 승인\n승인된 시나리오의 토큰으로 실행');
para(s,'실행 후','수거 시각별 모의 민원 비교\n최대 적재량과 잔여량 함께 확인\n가동률·완료 시각의 가정 확인\n실험 조건과 결과 해석 한계 제시',670);
foot(s,'예시 대화는 설명용이다. 특정 시각이 최적이라는 결과 수치를 가정하지 않는다.');

s=slide('8. 실행 전 검증','입력 하나의 범위와 여러 조건의 조합을 검사한다',base);
table(s,[['검증 대상','예시','처리'],['필수값 누락','주차 탐색 포기시간 없음','사용자에게 확인'],['지원 범위 초과','장량동 건물 27동','지원 범위 안내'],['조건 간 모순','앱 갱신 20분, 탐색 포기 10분','조합 오류 반환'],['과도한 수요','시설 규모에 비해 차량 수요 과다','부하 계산 후 경고·거절'],['자료 부재','선택 지역의 실시간 주차정보 없음','다른 자료 방식 안내']],[240,570,298],190,390,23);
foot(s,'기본값을 몰래 채우거나 지원하지 않는 기능을 실행한 것처럼 처리하지 않는다.');

s=slide('9. 적용 사례','세 시뮬레이터는 같은 절차와 서로 다른 규칙을 사용한다',base);
table(s,[['구분','장량동 쓰레기','행정동 쓰레기','공영주차장'],['분석 단위','원룸촌 한 블록','행정동 하나의 구역','도심 한 구역'],['규모','건물 1~26동','수거구역 1~40개','주차장 1~12곳'],['조건부 확인','다중 차량의\n배차 간격','적환장 사용 시\n왕복시간','API 선택 시\n초기 상태 조건'],['주요 결과','모의 민원·적재량','수거량·미수거량','탐색시간·포기율'],['공통 절차','확인·검증·승인·실행','확인·검증·승인·실행','확인·검증·승인·실행']],[220,296,296,296],188,390,22);
foot(s,'재사용하는 것은 서비스 절차이며, 각 모델의 계산 방식과 지원 범위는 유지한다.');

s=slide('9. 공영주차장 API','시설 자료와 초기 빈자리의 출처를 구분한다',base);
table(s,[['방식','시설 규모','초기 빈자리'],['가정값','사용자가 설정','가정 모델의 초기 상태'],['전국주차장 표준데이터','선택 시설의 주차면수','사용자가 빈자리와 가정 시각 지정'],['Parking_v1','시설·실시간 정보 연결','제공 지역의 API 잔여면수']],[320,350,438],192,280,23);
text(s,'API 사용 시 추가 확인: 기존 차량의 잔여 체류시간',86,505,1100,42,26,C.blue,true);
text(s,'요금·운영시간·좌표는 자동 적용하지 않는다.\n스냅샷을 초기 조건으로 사용하며 미래 빈자리 예측은 지원하지 않는다.',86,565,1100,72,24);
s.speakerNotes.text+='\n연동 주소: https://api.data.go.kr/openapi/tn_pubr_prkplce_info_api 및 https://apis.data.go.kr/B553881/Parking_v1. 두 API의 시설을 자동 병합하지 않는다. 표준데이터는 공영·노외 필터, 실시간 방식의 노외 여부는 별도 확인. API 조회시각과 실제 관측시각 구분. 현재 외부 호출 성공 여부를 이 발표에서 주장하지 않는다.';

s=slide('10. 기존 시뮬레이터의 전환','코드에서 템플릿 초안을 추출하고 제공자가 보완한다',base);
table(s,[['자동 추출의 근거','얻을 수 있는 초안 정보'],['설정 필드의 타입·초기값','값 유형과 기본값 후보'],['enum과 검증기','선택지와 허용 범위'],['필드를 사용하는 코드의 조건','질문 생성 조건 후보'],['필드의 설명 주석','질문 설명의 초안']],[460,648],190,320,24);
text(s,'제공자 검토: 모델 의미, 질문 문장, 적용 범위, 설정 대응',86,544,1108,45,26,C.orange,true);
text(s,'검토한 JSON을 서버 리소스로 배포한다. 요청마다 코드를 다시 추출하지 않는다.',86,610,1108,50,23);
s.speakerNotes.text+='\n근거: waste-sim-spring/template-generator 및 docs/guides/SUBTASK_TEMPLATE_FLOW.md. 자동 추출은 초안과 근거·검토 항목을 제공한다. 완전 자동 서비스 생성이나 작업시간 감소의 실측 결과로 해석하지 않는다.';

s=slide('11. 평가 계획','연구 질문별로 검증할 항목',base);
table(s,[['연구 질문','비교·측정할 내용','평가 방법'],['RQ1\n제공자 작업','템플릿 작성시간\n초안 수정량\n추가 연결 코드량','수작업 작성과\n초안 추출 후 보완 비교'],['RQ2\n사용자 실험 구성','조건 누락·설정 일치\n불필요한 질문\n오류 차단·완료 여부','정상·누락·모순·범위 초과\n요청으로 동작 검증']],[240,455,413],205,325,24);
foot(s,'평가 계획이다. 이 자료에서는 작업 감소율이나 성공률을 측정 결과로 제시하지 않는다.');

s=slide('11. 구현 범위와 한계','확인한 구조와 앞으로 검증할 효과',base);
para(s,'현재 코드에 있는 기능','능력 카드 등록과 서버 탐색\n조건부 질문과 응답 검증\n실행 설정 생성과 사용자 승인\n주차장 API 초기 상태 연결');
para(s,'추가 검증이 필요한 부분','제공자의 실제 작업시간 감소\n일반 사용자의 구성 성공률\n더 다양한 엔진으로의 확장\n모의 결과와 실제 운영 자료의 일치',670);
foot(s,'서비스 구성의 정확성과 시뮬레이션 모델 자체의 예측 정확도는 별도로 평가한다.');

s=slide('12. 결론','모델별 규칙을 명시하고 실험 구성 절차를 공유한다',base);
rows(s,[['제공자','지원 범위를 능력 카드에, 결정 규칙을 템플릿에 정의한다.'],['브로커','등록된 정보로 요청에 적합한 시뮬레이터를 찾는다.'],['LLM과 서버','LLM은 대화하고 서버는 규칙을 판정하며 설정을 검증한다.'],['사용자','확정된 조건을 확인·승인하고 결과와 한계를 전달받는다.']]);
foot(s,'향후 평가: 제공자 작업 부담과 사용자 실험 구성의 개선 효과를 측정한다.');

await fs.mkdir(path.join(ROOT,'preview'),{recursive:true});
for(let i=0;i<p.slides.items.length;i++){
 const b=await p.export({slide:p.slides.items[i],format:'png',scale:1});
 await fs.writeFile(path.join(ROOT,'preview',`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await b.arrayBuffer()));
}
await fs.writeFile('final.proto.json',JSON.stringify(p.toProto()));
const candidate=path.join(ROOT,'build','candidate.pptx');
await (await PresentationFile.exportPptx(p)).save(candidate);
const {finalizePresentation}=await import(pathToFileURL(path.join(SKILL,'container_tools/artifact_tool_utils.mjs')).href);
const nativeLink=fs.link; fs.link=async (a,b)=>{try{return await nativeLink(a,b)}catch(e){if(e.code!=='EPERM')throw e;return fs.copyFile(a,b,1)}};
const result=await finalizePresentation({workspaceDir:ROOT,candidatePath:candidate,finalPath:path.join(ROOT,'deliverable','시뮬레이션_서비스_명세등록_추가본.pptx'),pythonExecutable:PY,integrityValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_layout_geometry.py'),layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit',...tableOwners.flatMap(n=>['--require-native-table-slide',String(n)])],requiredNativeTableOwnerSlides:tableOwners,fontPolicy:{basis:'reference',families:[FONT],referencePath:SOURCE,referenceSha256:crypto.createHash('sha256').update(await fs.readFile(SOURCE)).digest('hex')},verifyArtifactToolImport:true,receiptPath:path.join(ROOT,'build','validation-registration.json')});
console.log(JSON.stringify(result));







