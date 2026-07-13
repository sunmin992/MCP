import csv

INPUT_PATH = r"C:\Users\User\Downloads\response_1783932255990.csv"
OUTPUT_PATH = r"C:\Dev\MCP\waste-sim-spring\response_filtered.csv"

KEYWORDS = ["두산위브", "포항온천", "장량", "양덕", "장성", "장원", "장성", "침촌"]


def main():
    with open(INPUT_PATH, newline="", encoding="utf-8-sig") as f_in:
        reader = csv.DictReader(f_in)
        matched = [
            row for row in reader
            if any(kw in row["begin_node_nm"] or kw in row["end_node_nm"] for kw in KEYWORDS)
        ]
        fieldnames = reader.fieldnames

    # 같은 구간(시작-끝 노드)이 여러 날짜에 걸쳐 중복되면 가장 최신
    # collection_dt 데이터만 남긴다.
    latest_by_segment = {}
    for row in matched:
        key = (row["begin_node_nm"], row["end_node_nm"])
        existing = latest_by_segment.get(key)
        if existing is None or row["collection_dt"] > existing["collection_dt"]:
            latest_by_segment[key] = row

    rows = list(latest_by_segment.values())

    with open(OUTPUT_PATH, "w", newline="", encoding="utf-8-sig") as f_out:
        writer = csv.DictWriter(f_out, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"{len(matched)}건 매칭 -> 중복 제거 후 {len(rows)}건 저장 -> {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
