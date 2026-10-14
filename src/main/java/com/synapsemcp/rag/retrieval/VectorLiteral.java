package com.synapsemcp.rag.retrieval;

/** Renders a float vector as the pgvector text literal format (e.g. {@code "[0.1,0.2,0.3]"}). */
final class VectorLiteral {

    private VectorLiteral() {
    }

    static String of(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
