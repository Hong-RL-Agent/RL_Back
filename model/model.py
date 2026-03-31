from fastapi import FastAPI
from pydantic import BaseModel
import numpy as np
import tensorflow as tf
from tensorflow.keras import layers
import uvicorn

app = FastAPI()

# --- [신규 추가] 보상 계산 함수 ---
def calculate_balanced_reward(has_error, is_new_path, is_action_success):
    """
    QA AI의 철학: "웹페이지의 실패(에러)가 곧 나의 대성공이다!"
    """
    reward = 0.0 

    # 1. 🎯 [최고의 성과] 에러 탐지 성공 (웹페이지 고장냄)
    if has_error:
        reward = 10.0   # 버그를 찾았을 때만 확실하고 큰 보상을 지급!
        print(f"🎯 [BUG FOUND] 치명적 에러 발견! 대성공 보상: +{reward}")
        return reward

    # 2. 🚶 [아쉬운 결과] 행동은 성공했지만 웹페이지가 '정상 작동'함
    if is_action_success:
        if is_new_path:
            # 새로운 곳을 찔러봤지만 버그가 없었음. 점수를 주지 않음 (0점)
            reward = 0.0  
            print(f"🚶 [NORMAL] 정상 작동 확인 (버그 없음). 보상: {reward}")
        else:
            # 맨날 누르던 정상 버튼을 또 눌러서 시간만 낭비함 (감점)
            reward = -0.1 
            print(f"💤 [TIME WASTED] 의미 없는 반복 행동. 벌점: {reward}")
    
    # 3. ⚠️ [행동 실패] 클릭할 수 없는 곳을 누르거나 타임아웃 발생
    else:
        # AI 자체가 헛발질을 한 것이므로 감점
        reward = -1.0 
        print(f"⚠️ [AGENT FAIL] 잘못된 조작. 벌점: {reward}")

    return reward


# STEP 1. 추론 모델 정의 및 학습
def build_and_train_model():
    # 데이터: [콘솔에러(0/1), 응답시간(ms), UI겹침도(0~1)]
    X_train = np.array([
        [0, 150, 0.0], [0, 200, 0.1], [0, 100, 0.05], # 정상 데이터
        [1, 500, 0.1], [0, 3000, 0.2], [0, 200, 0.8]  # 결함 데이터
    ], dtype=float)
    y_train = np.array([0, 0, 0, 1, 1, 1], dtype=float)

    model = tf.keras.Sequential([
        layers.Dense(units=4, activation='relu', input_shape=[3]),
        layers.Dense(units=1, activation='sigmoid')
    ])

    model.compile(optimizer='adam', loss='binary_crossentropy', metrics=['accuracy'])
    print("AI 추론 모델 학습 중...")
    model.fit(X_train, y_train, epochs=1000, verbose=0)
    print("모델 학습 완료!")
    return model

# 서버가 켜질 때 모델을 딱 한 번만 학습시켜서 메모리에 올려둡니다.
inference_model = build_and_train_model()

# STEP 2. 자바에서 받을 데이터 형식 정의
class AgentData(BaseModel):
    console_errors: int
    load_time: float
    ui_overlap_score: float
    # RL 보상용 추가 파라미터 (Java에서 안 보내면 기본값 사용)
    is_new_path: bool = False 
    is_action_success: bool = True 

# --- STEP 3. 예측 API 엔드포인트 (/predict) ---
@app.post("/predict")
async def predict_defect(data: AgentData):
    print(f"\n[데이터 수신] 에러: {data.console_errors}, 로딩시간: {data.load_time}ms")
    
    # 1. 신경망 모델을 이용한 결함 확률 예측
    input_data = np.array([[data.console_errors, data.load_time, data.ui_overlap_score]])
    prediction = inference_model.predict(input_data, verbose=0)
    defect_prob = float(prediction[0][0])
    
    # 2. 판정 로직
    if defect_prob > 0.5:
        action = "stop"  # 결함 발견 시 자바에게 탐색 중지 명령
        message = "판정: 결함(Bug)이 의심됩니다!"
    else:
        action = "continue" # 정상이면 다음 버튼 클릭 명령
        message = "판정: 정상 상태입니다."
        
    print(message)
    
    # 3. 강화학습 보상(Reward) 계산 실행
    has_error_flag = data.console_errors > 0
    calculated_reward = calculate_balanced_reward(
        has_error=has_error_flag, 
        is_new_path=data.is_new_path, 
        is_action_success=data.is_action_success
    )
    
    # 4. 자바에게 다시 돌려줄 응답(JSON)에 보상값(reward) 추가
    return {
        "action": action, 
        "defect_probability": defect_prob,
        "message": message,
        "reward": calculated_reward  # 자바 쪽에서 이 값을 받아서 Q-table 등을 업데이트할 수 있습니다.
    }

# STEP 4. 메인 실행부
if __name__ == "__main__":
    print("파이썬 추론 서버 시작 (포트 8000)")
    uvicorn.run(app, host="0.0.0.0", port=8000)