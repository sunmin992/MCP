package demo;

/** 진입점. 엔진을 필드로 들고 부른다. */
public class DemoService {

    private final DemoEngine engine = new DemoEngine();

    public DemoResult runAll(DemoConfig c) {
        return engine.run(c);
    }
}
