package com.jdesk.util;

import java.util.Objects;

/**
 * Immutable triple — three values.
 */
public class Triple<A, B, C> {

    public final A first;
    public final B second;
    public final C third;

    private Triple(A first, B second, C third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public static <A, B, C> Triple<A, B, C> of(A first, B second, C third) {
        return new Triple<>(first, second, third);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Triple<?, ?, ?> t)) return false;
        return Objects.equals(first, t.first)
                && Objects.equals(second, t.second)
                && Objects.equals(third, t.third);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second, third);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ", " + third + ")";
    }
}
