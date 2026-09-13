package com.wastesim.registry;

import java.util.List;

/**
 * 자산 하나의 등록 계약.
 *
 * <p>상태가 둘뿐인 이유는 셋째 상태를 둘 근거가 없기 때문이다. 자동 추출 결과는 언제나
 * {@link Status#PROPOSED}에서 시작한다 — 추출에 성공했다는 것은 문장을 찾았다는 뜻이지
 * 그 값이 맞다는 뜻이 아니다. {@code SpanVerifier}가 인용 문자열의 포함 여부만 검사한다는
 * 사실이 이 구분의 근거다.
 *
 * @param boundInputFields 이 계약이 덮는다고 주장하는 실행 입력 필드들.
 *                         <b>주장이지 사실이 아니다</b> — 사실은
 *                         {@link SimulationConfigFields}가 코드에서 읽는다
 * @param trialGuarantees  시험 실행이 보증하는 범위. 비어 있으면 아무것도 보증하지 않는다
 */
public record AssetContract(
        String assetId,
        String version,
        String contentDigest,
        Status status,
        List<String> ruleRefs,
        List<String> adapterRefs,
        List<String> evidenceRefs,
        List<String> boundInputFields,
        boolean trialSupported,
        List<String> trialGuarantees) {

    public enum Status { PROPOSED, VERIFIED }

    public AssetContract {
        ruleRefs = ruleRefs == null ? List.of() : List.copyOf(ruleRefs);
        adapterRefs = adapterRefs == null ? List.of() : List.copyOf(adapterRefs);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        boundInputFields = boundInputFields == null ? List.of() : List.copyOf(boundInputFields);
        trialGuarantees = trialGuarantees == null ? List.of() : List.copyOf(trialGuarantees);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 테스트가 한 항목만 바꿔 가며 쓰기 위한 조립기. */
    public static final class Builder {
        private String assetId;
        private String version;
        private String contentDigest;
        private Status status = Status.PROPOSED;
        private List<String> ruleRefs = List.of();
        private List<String> adapterRefs = List.of();
        private List<String> evidenceRefs = List.of();
        private List<String> boundInputFields = List.of();
        private boolean trialSupported = false;
        private List<String> trialGuarantees = List.of();

        public Builder assetId(String v) { this.assetId = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder contentDigest(String v) { this.contentDigest = v; return this; }
        public Builder status(Status v) { this.status = v; return this; }
        public Builder ruleRefs(List<String> v) { this.ruleRefs = v; return this; }
        public Builder adapterRefs(List<String> v) { this.adapterRefs = v; return this; }
        public Builder evidenceRefs(List<String> v) { this.evidenceRefs = v; return this; }
        public Builder boundInputFields(java.util.Collection<String> v) {
            this.boundInputFields = List.copyOf(v); return this;
        }
        public Builder trialSupported(boolean v) { this.trialSupported = v; return this; }
        public Builder trialGuarantees(List<String> v) { this.trialGuarantees = v; return this; }

        public AssetContract build() {
            return new AssetContract(assetId, version, contentDigest, status, ruleRefs,
                    adapterRefs, evidenceRefs, boundInputFields, trialSupported, trialGuarantees);
        }
    }
}
