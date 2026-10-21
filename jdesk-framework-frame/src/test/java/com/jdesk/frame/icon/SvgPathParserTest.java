package com.jdesk.frame.icon;

import org.junit.jupiter.api.Test;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SvgPathParserTest {

    /** Numbers that can't fill a group, such as an odd count, are dropped; full groups still draw and parsing ends. */
    @Test
    void leftoverNumbersThatCannotFillAGroupAreDropped() {
        Map<String, Point2D> cases = Map.of(
                "M0 0 L10 10 20", new Point2D.Double(10, 10),
                "M0 0 C1 1 2 2 3 3 4", new Point2D.Double(3, 3),
                "M0 0 Q1 1 2 2 3", new Point2D.Double(2, 2),
                "M0 0 A1 1 0 0 1 5 5 9", new Point2D.Double(5, 5),
                "M5 5 7", new Point2D.Double(5, 5));
        cases.forEach((data, end) -> assertEquals(end, parse(data).getCurrentPoint(), data));
    }

    /** Arc flags written together, as minifiers do, are split per character and still reach the right point. */
    @Test
    void arcFlagsWrittenWithoutSeparatorsAreSplitPerCharacter() {
        assertEquals(new Point2D.Double(50, 0), parse("M0 0a5 5 0 0150 0").getCurrentPoint());
        assertEquals(new Point2D.Double(0.5, 0), parse("M0 0a5 5 0 01.5 0").getCurrentPoint());
    }

    private static Path2D parse(String data) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> SvgPathParser.parse(data, Path2D.WIND_NON_ZERO),
                "parsing got stuck: " + data);
    }
}
