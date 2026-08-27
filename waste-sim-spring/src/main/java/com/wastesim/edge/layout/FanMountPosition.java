package com.wastesim.edge.layout;

/** 40 mm 팬 장착 위치 6곳. 효율은 2026-08-27 임시 예측 시트의 경험적 계수다. */
public enum FanMountPosition {
    BOTTOM("하단", 0, Side.CENTER, 0.95), TOP("상단", 2, Side.CENTER, 0.90),
    LEFT_BOTTOM("좌측 하단", 0, Side.LEFT, 0.78), LEFT_TOP("좌측 상단", 2, Side.LEFT, 0.82),
    RIGHT_BOTTOM("우측 하단", 0, Side.RIGHT, 0.78), RIGHT_TOP("우측 상단", 2, Side.RIGHT, 0.82);
    public enum Side { CENTER, LEFT, RIGHT }
    private final String koLabel; private final int level; private final Side side; private final double efficiency;
    FanMountPosition(String koLabel,int level,Side side,double efficiency){this.koLabel=koLabel;this.level=level;this.side=side;this.efficiency=efficiency;}
    public String koLabel(){return koLabel;} public int level(){return level;} public Side side(){return side;}
    public double efficiency(){return efficiency;} public String wire(){return name().toLowerCase();}
    public static FanMountPosition parse(String raw){
        if(raw==null||raw.trim().isEmpty()) return null; String s=raw.trim();
        for(var p:values()) if(p.koLabel.equals(s)) return p;
        String key=s.toUpperCase().replace("-","_").replace(" ","_");
        for(var p:values()) if(p.name().equals(key)) return p; return null;
    }
}
