package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.ses.SesCatalog;
import com.wastesim.template.TemplateCatalog;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GetSesToolTest {

    @Test
    void get_ses_는_원본을_그대로_낸다() throws Exception {
        ObjectMapper m = new ObjectMapper();
        GetSesTool tool = new GetSesTool(new SesCatalog(new TemplateCatalog()));
        assertEquals("get_ses", tool.toolName());
        ToolResult r = tool.call(m.createObjectNode());
        JsonNode expected;
        try (InputStream in = getClass().getResourceAsStream("/ses/jangnyang-ses.json")) {
            expected = m.readTree(in);
        }
        assertEquals(expected, m.readTree((String) r.result()));
    }
}
