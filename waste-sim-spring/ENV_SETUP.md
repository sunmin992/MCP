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

### 모델만 바꿔 벤치마크 (파일 수정 불필요)

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

- `.gitignore`로 `target/`은 추적에서 뺐다. 다른 머신에서 이 커밋을 pull하면
  그쪽 `target/`이 삭제되는데, 빌드 산출물이라 `mvn package` 한 번이면 복구된다.
- 키를 실수로 커밋했다면 커밋을 되돌리는 것만으로는 부족하다. 해당 키를
  즉시 폐기(revoke)하고 새로 발급받을 것.
