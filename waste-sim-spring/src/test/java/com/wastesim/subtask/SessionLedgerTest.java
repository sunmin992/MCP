package com.wastesim.subtask;

import com.wastesim.ledger.ParameterLedger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원장이 세션과 같은 수명을 사는가.
 *
 * <p>별도 저장소로 빼면 세션만 저장되고 원장은 저장되지 않는 순간이 생기고, 그 순간에
 * 둘은 다른 사실을 말한다. 같은 객체에 매달아 두면 그 자리가 없어진다.
 */
class SessionLedgerTest {

    private static JangnyangSubtaskDefinition def() {
        return new JangnyangSubtaskCatalog().latest();
    }

    @Test
    void 새_세션의_원장은_비어_있다() {
        JangnyangSubtaskSession session = new JangnyangSubtaskSession("k", def());
        assertNotNull(session.ledger());
        assertEquals(List.of(), session.ledger().parameterIds());
    }

    @Test
    void 원장은_같은_인스턴스를_계속_돌려준다() {
        JangnyangSubtaskSession session = new JangnyangSubtaskSession("k", def());
        ParameterLedger first = session.ledger();
        assertSame(first, session.ledger(),
                "호출할 때마다 새 원장을 주면 쌓은 이력이 사라진다");
    }

    @Test
    void 세션마다_원장이_다르다() {
        JangnyangSubtaskSession a = new JangnyangSubtaskSession("a", def());
        JangnyangSubtaskSession b = new JangnyangSubtaskSession("b", def());
        assertNotSame(a.ledger(), b.ledger());
    }
}
