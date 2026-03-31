from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
import google.generativeai as genai
import uvicorn
import time

app = FastAPI()

GOOGLE_API_KEY = "AIzaSyCv4naxUy34DdHOovCC-0uoyXwmMRw4r5E"

genai.configure(api_key=GOOGLE_API_KEY)

# Gemini 모델
model = genai.GenerativeModel("gemini-2.5-flash")


class ReportRequest(BaseModel):
    logs: list[str]


@app.post("/report", response_class=HTMLResponse)
def generate_report(req: ReportRequest):

    start = time.time()

    print(f"[서버] 로그 수신: {len(req.logs)}줄")

    log_text = "\n".join(req.logs)

    prompt = f"""
당신은 10년차 시니어 QA 자동화 엔지니어입니다.

다음 UI 테스트 로그를 분석하여 **HTML 형식의 전문적인 버그 리포트**를 작성하세요.

반드시 포함할 것:

1. 핵심 요약
2. Step별 행동 분석
3. 오류 발생 위치
4. 기술적 원인 분석
5. 해결 방안
6. 개발자용 코드 수정 예시

조건:
- Markdown 금지
- HTML만 출력
- 카드형 UI 리포트
- 코드 블록은 <pre><code> 사용

테스트 로그:

{log_text}
"""

    try:
        response = model.generate_content(prompt)

        elapsed = time.time() - start

        print(f"[서버] Gemini 리포트 생성 완료 ({elapsed:.2f}초)")

        return response.text

    except Exception as e:

        print("[서버 오류]", e)

        return f"""
<div style="font-family:Arial;padding:40px">
<h1>테스트 자동화 버그 리포트</h1>
<h2>LLM 분석 실패</h2>

<p>Gemini API 호출 중 오류가 발생했습니다.</p>

<pre>{str(e)}</pre>

<h3>수집 로그</h3>

<pre>
{log_text}
</pre>

</div>
"""


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001)