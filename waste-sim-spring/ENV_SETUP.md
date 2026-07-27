# 머신별 환경 설정 — 윈도우 2대 + 맥북 1대

목표: **어느 컴퓨터에서든 실행 명령은 `mvn spring-boot:run` 하나로 동일하게.**
머신마다 다른 값은 전부 환경변수로 빼서 git에 안 남게 한다. API 키는 어떤
파일에도 적지 않는다.

---

## 1. 설정이 갈리는 지점

| 항목 | 어디서 정하나 | git 커밋 |
|---|---|---|
| LLM 백엔드(Ollama/OpenAI) | Spring 프로파일 (`application-{ollama,openai}.properties`) | O — 비밀값 없음 |
| API 키 | `OPENAI_API_KEY` 환경변수 | X — 절대 커밋 금지 |
| Python DEVS 엔진 경로 | `WASTE_SIM_PYTHON_*` 환경변수 | X |

`application.properties`에는 `spring.profiles.default=openai`만 있고 URL·모델
값은 프로파일 파일에 있다. **이 파일들은 머신마다 고치지 말 것** — 고치는 순간
3대가 서로 충돌한다.

---

## 2. 머신별 1회 설정

### 윈도우 A — Ollama 벤치마크용

PowerShell에서 (`setx`는 영구 설정, **새 터미널부터** 적용):

```powershell
setx SPRING_PROFILES_ACTIVE "ollama"
```

키는 필요 없다(Ollama는 인증을 검사하지 않음).

### 윈도우 B — GPT API

```powershell
setx SPRING_PROFILES_ACTIVE "openai"
setx OPENAI_API_KEY "sk-..."
```

> `setx`로 넣은 값은 현재 열려 있는 창엔 반영되지 않는다. 터미널을 새로 열 것.

### 맥북 — GPT API

`~/.zshrc` 맨 아래에 추가한 뒤 `source ~/.zshrc`:

```bash
export SPRING_PROFILES_ACTIVE=openai
export OPENAI_API_KEY="sk-..."
```

`SPRING_PROFILES_ACTIVE`는 기본값이 `openai`라 사실 생략해도 되지만, 명시해두면
나중에 헷갈리지 않는다.

맥북에서 Python DEVS 엔진(`python-devs`)까지 쓰려면 추가로 — 맥엔 `python`
명령이 없고 `python3`만 있으며, 기본 경로는 윈도우 것이라 반드시 덮어써야 한다:

```bash
export WASTE_SIM_PYTHON_EXECUTABLE=python3
export WASTE_SIM_PYTHON_PROJECT_ROOT=/Users/사용자명/경로/adev-master
```

---

## 3. 실행

설정을 마쳤으면 3대 모두 동일하다.

```bash
mvn spring-boot:run
```

http://localhost:8090

### 일회성으로 다른 백엔드 써보기

영구 설정을 건드리지 않고 그 실행에만 적용된다.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=ollama
```

### 모델만 바꿔 실행 (파일 수정 불필요)

```bash
OPENAI_MODEL=gemma2:9b mvn spring-boot:run
```

윈도우 PowerShell에서는:

```powershell
$env:OPENAI_MODEL="gemma2:9b"; mvn spring-boot:run
```

우선순위는 **환경변수 > 프로파일 파일 > 기본값** 이라, 환경변수를 주면 프로파일
파일 값을 항상 이긴다.

---

## 3-1. 벤치마크 스크립트 (`llm_benchmark.py`)

> **주의: 이 스크립트는 Spring 프로파일을 읽지 않는다.** 앱과 완전히 별개로
> 자기 환경변수만 보고, 비교 대상 모델 목록도 스크립트 안의 `MODELS`에 하드코딩돼
> 있다. 즉 `SPRING_PROFILES_ACTIVE`를 바꿔도 벤치마크 동작은 달라지지 않는다.

| 환경변수 | 용도 | 기본값 |
|---|---|---|
| `OLLAMA_URL` | 로컬 모델 엔드포인트 | `http://localhost:11434/v1/chat/completions` |
| `OPENAI_API_URL` | `gpt-4o-mini` 항목이 쓸 엔드포인트 | `https://api.openai.com/v1/chat/completions` |
| `OPENAI_API_KEY` | 없으면 OpenAI 모델은 자동 건너뜀 | (없음) |
| `EXCLUDE_MODELS` | 쉼표로 구분해 특정 모델 제외 | (없음) |

### 윈도우 A (Ollama 설치된 머신)

로컬 4개 모델만 돌리고 GPT는 비용·시간 때문에 건너뛸 때:

```powershell
$env:EXCLUDE_MODELS="gpt-4o-mini"; python llm_benchmark.py
```

### 맥북 / Ollama 없는 머신

로컬 모델이 설치돼 있지 않으면 그 4개는 전부 연결 실패로 잡히므로 미리 제외한다.
맥에는 `python` 명령이 없으니 `python3`으로 실행할 것:

```bash
EXCLUDE_MODELS="llama3.2:3b,qwen2.5:7b,gemma:2b,gemma2:9b" python3 llm_benchmark.py
```

### ⚠️ `OPENAI_API_URL`은 되도록 설정하지 말 것

앱과 벤치마크가 **같은 변수를 공유하면서 의미가 다르다.** ollama 프로파일은 이
변수가 없어도 기본값으로 localhost를 가리키므로 굳이 설정할 필요가 없는데, 만약
앱을 Ollama로 돌리려고 이 변수를 localhost로 걸어두면 **벤치마크의 `gpt-4o-mini`
요청까지 Ollama로 가버린다**(존재하지 않는 모델이라 실패). 백엔드 전환은 이 변수가
아니라 프로파일(`SPRING_PROFILES_ACTIVE`)로 하는 것이 원칙이다.

### 결과 파일

`benchmark_report.md`와 `benchmark_detail.log`는 실행할 때마다 **덮어써진다**(추가
아님).

`benchmark_report.md`는 매 실행 때 통째로 새로 쓰여 3대에서 충돌하므로 추적하지
않는다(`.gitignore`). 각 머신에서 돌린 결과는 그 머신 로컬에만 남으므로, 공유할
수치는 커밋 메시지나 문서에 적어 남길 것. `benchmark_detail.log`(실패 케이스 원문)만
진단용으로 커밋한다.

---

## 4. 현재 설정 확인

어떤 값이 실제로 주입됐는지 보려면(값이 마스킹되지 않게 임시로 노출):

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--management.endpoints.web.exposure.include=env,health --management.endpoint.env.show-values=ALWAYS"
```

띄운 뒤 다른 터미널에서:

```bash
curl -s http://localhost:8090/actuator/env/openai.model
```

응답의 `propertySources[].name`을 보면 값이 프로파일 파일에서 왔는지
`systemEnvironment`(환경변수)에서 왔는지 알 수 있다. **이 옵션은 키까지 평문으로
노출하므로 확인용으로만 쓰고 기본 설정에는 넣지 말 것.**

---

## 5. 주의

- `.gitignore`로 아래 머신별 산출물을 추적에서 뺐다. 다른 머신에서 이 커밋을
  pull하면 그쪽 파일이 git에서만 삭제되는데(디스크는 보존), 전부 재생성되는
  것들이라 문제없다.
  - `target/` — `mvn package` 한 번이면 복구
  - `__pycache__/*.pyc` — 파이썬 버전마다 달라 3대가 서로 충돌했다(310·314가
    같이 추적돼 있었음). 실행하면 자동 재생성
  - `app.log` / `app-err.log` — 실행 로그. 머신별 절대경로·PID가 박혀 매번 충돌
  - `.claude/settings.local.json` — 윈도우 경로·PowerShell 명령이 든 머신 전용 설정
  - `benchmark_report.md` — 벤치마크 실행마다 통째로 덮어써짐. `python llm_benchmark.py`로 재생성
  - `*.docx` — 문서 변환 결과물. 바이너리라 병합이 불가능하므로 원본 마크다운만
    커밋하고 변환본은 각자 로컬에서 생성
- **`.gitignore`는 이미 추적 중인 파일에는 효과가 없다.** 위 파일들도 `.gitignore`에
  패턴은 있었지만 이전에 커밋돼 있어서 계속 충돌했다. 새로 무시하려면 패턴 추가만으로는
  부족하고 `git rm --cached <파일>`로 인덱스에서 빼야 한다.
- 키를 실수로 커밋했다면 커밋을 되돌리는 것만으로는 부족하다. 해당 키를
  즉시 폐기(revoke)하고 새로 발급받을 것.
