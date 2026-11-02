package com.wastesim.ses;

import com.wastesim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SesCatalogTest {

    private final TemplateCatalog templates = new TemplateCatalog();

    @Test
    void 기본_원본은_검사를_통과한다() {
        assertFalse(new SesCatalog(templates).tree().decisions().isEmpty());
    }

    @Test
    void 템플릿과_sesId_버전이_다르면_뜨지_않는다() {
        assertThrows(IllegalStateException.class, () -> new SesCatalog(templates, "/ses/ses-bad-version.json"));
        assertThrows(IllegalStateException.class, () -> new SesCatalog(templates, "/ses/ses-bad-id.json"));
    }

    @Test
    void 설정에_없는_configField_는_뜨지_않는다() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SesCatalog(templates, "/ses/ses-bad-field.json"));
        assertTrue(e.getMessage().contains("noSuchField"), e.getMessage());
    }
}
