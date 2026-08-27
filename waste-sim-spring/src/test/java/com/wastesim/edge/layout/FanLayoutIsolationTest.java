package com.wastesim.edge.layout;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FanLayoutIsolationTest {
    @Test void doesNotReferenceThermalStack()throws Exception{Path d=Path.of("src/main/java/com/wastesim/edge/layout");for(Path f:Files.list(d).filter(p->p.toString().endsWith(".java")).toList()){String s=Files.readString(f).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("(?m)//.*$","");for(String type:List.of("ThermalSimulator","HeatsinkThermalModel","ThermalParams","ThermalRun"))assertFalse(s.contains(type),f+" -> "+type);}}

    @Test void onlyUsesFanArraySpecForSourceStatus()throws Exception{
        Path d=Path.of("src/main/java/com/wastesim/edge/layout");
        for(Path f:Files.list(d).filter(p->p.toString().endsWith(".java")).toList()){
            String s=Files.readString(f).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("(?m)//.*$","");
            if(s.contains("FanArraySpec")){
                assertTrue(s.contains("FanArraySpec.SourceStatus"),f+" must use only FanArraySpec.SourceStatus");
                assertFalse(s.matches("(?s).*FanArraySpec\\s*[.(](?!SourceStatus).*"),f+" must not construct or call FanArraySpec");
            }
        }
    }
}
