package com.wastesim.edge.layout;

/** 함체 안으로 넣는 흡기와 밖으로 빼는 배기를 구분한다. */
public enum FanFlowRole {
    INTAKE("흡기"), EXHAUST("배기");
    private final String koLabel; FanFlowRole(String koLabel){this.koLabel=koLabel;}
    public String koLabel(){return koLabel;} public String wire(){return name().toLowerCase();}
    public static FanFlowRole parse(String raw){if(raw==null)return null;String s=raw.trim();for(var r:values())if(r.koLabel.equals(s)||r.name().equalsIgnoreCase(s))return r;return null;}
}
