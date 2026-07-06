package com.wastesim.tool;

import com.wastesim.model.OccupationType;
import com.wastesim.model.SimulationConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SimulationConfig 서버측 검증 — 유일한 검증 진실 원천(single source of truth).
 * REST·MCP·채팅 파이프라인이 모두 이 검증기를 통과해야 실행된다(베이스라인 "서버가 검증을 소유").
 */
@Component
public class SimulationConfigValidator {

    public ValidationResult validate(SimulationConfig c) {
        List<ValidationError> errs = new ArrayList<>();
        if (c == null) {
            errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "config", "설정이 없습니다."));
            return ValidationResult.fail(errs);
        }

        int t = c.getCollectionTimeMinutes();
        if (t < 0 || t > 1439)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "collectionTime", "수거 시각은 00:00~23:59(0~1439분) 범위여야 합니다."));

        if (c.getDays() < 1 || c.getDays() > 365)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "days", "기간(days)은 1~365 사이여야 합니다."));

        if (c.getSeeds() < 1 || c.getSeeds() > 100)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "seeds", "반복(seeds)은 1~100 사이여야 합니다."));

        if (c.getLeaveSigma() < 0)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "leaveSigma", "외출 분산(leaveSigma)은 0 이상이어야 합니다."));

        if (c.getWasteSigma() < 0)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "wasteSigma", "배출 변동(wasteSigma)은 0 이상이어야 합니다."));

        if (c.getCapacity() <= 0)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "capacity", "수거장 용량(capacity)은 0보다 커야 합니다."));

        if (c.getThreshold() < 0 || c.getThreshold() > 1)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "threshold", "민원 임계(threshold)는 0~1 사이여야 합니다."));

        if (c.getNumBuildings() < 1)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "numBuildings", "건물 수는 1 이상이어야 합니다."));

        if (c.getResidentsPerBuilding() < 1)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "residentsPerBuilding", "동당 인원은 1 이상이어야 합니다."));

        if (c.getOccupationMix() != null) {
            for (String occ : c.getOccupationMix()) {
                try {
                    OccupationType.fromName(occ);
                } catch (Exception ex) {
                    errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "occupationMix", "알 수 없는 직업: " + occ));
                }
            }
        }

        return errs.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errs);
    }
}
