def simulate_waste_collection(collection_time, total_residents=1000, initial_complaints_rate=0.1):
    # 초기 민원 수
    initial_complaints = int(total_residents * initial_complaints_rate)
    
    # 수거 시간에 따른 민원 감소 비율
    if collection_time < 8:  # 오전 8시 이전
        complaint_reduction_rate = 0.5  # 50% 감소
    elif 8 <= collection_time < 18:  # 오전 8시부터 오후 6시까지
        complaint_reduction_rate = 0.2  # 20% 감소
    else:  # 오후 6시 이후
        complaint_reduction_rate = 0.1  # 10% 감소

    # 최종 민원 수 계산
    reduced_complaints = int(initial_complaints * (1 - complaint_reduction_rate))
    
    return reduced_complaints

# 시뮬레이션 실행 예시
collection_times = [6, 8, 12, 18, 20]  # 수거 차량의 운행 시간
results = {}

for time in collection_times:
    final_complaints = simulate_waste_collection(time)
    results[time] = final_complaints

# 결과 출력
for time, complaints in results.items():
    print(f"수거 시간: {time}시, 최종 민원 수: {complaints}명")
