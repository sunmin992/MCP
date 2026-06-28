package com.wastesim.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SimulationConfig {

    private int collectionTimeMinutes = 12 * 60;   // 12:00 (720 min)
    private int days = 30;
    private int seeds = 30;
    private double leaveSigma = 30.0;
    private double wasteSigma = 0.3;
    private double capacity = 30.0;
    private double threshold = 0.8;
    private int numBuildings = 4;
    private int residentsPerBuilding = 25;

    // Getters & setters

    public int getCollectionTimeMinutes() { return collectionTimeMinutes; }
    public void setCollectionTimeMinutes(int v) { this.collectionTimeMinutes = v; }

    public int getDays() { return days; }
    public void setDays(int v) { this.days = v; }

    public int getSeeds() { return seeds; }
    public void setSeeds(int v) { this.seeds = v; }

    public double getLeaveSigma() { return leaveSigma; }
    public void setLeaveSigma(double v) { this.leaveSigma = v; }

    public double getWasteSigma() { return wasteSigma; }
    public void setWasteSigma(double v) { this.wasteSigma = v; }

    public double getCapacity() { return capacity; }
    public void setCapacity(double v) { this.capacity = v; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double v) { this.threshold = v; }

    public int getNumBuildings() { return numBuildings; }
    public void setNumBuildings(int v) { this.numBuildings = v; }

    public int getResidentsPerBuilding() { return residentsPerBuilding; }
    public void setResidentsPerBuilding(int v) { this.residentsPerBuilding = v; }

    /** "HH:MM" 형식 문자열로 수거 시각 반환 */
    public String getCollectionTimeLabel() {
        return String.format("%02d:%02d", collectionTimeMinutes / 60, collectionTimeMinutes % 60);
    }

    /** "HH:MM" 문자열로 수거 시각 설정 */
    public void setCollectionTimeLabel(String hhmm) {
        String[] parts = hhmm.split(":");
        int h = Integer.parseInt(parts[0]);
        int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        this.collectionTimeMinutes = h * 60 + m;
    }
}
