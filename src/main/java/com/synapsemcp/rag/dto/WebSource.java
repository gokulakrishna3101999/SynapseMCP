package com.synapsemcp.rag.dto;

/** One WEB_SEARCH citation, per design.md section 7.3 ("webSources"). */
public record WebSource(String title, String url) {
}
