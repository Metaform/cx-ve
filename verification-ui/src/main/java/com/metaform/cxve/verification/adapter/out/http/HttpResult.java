package com.metaform.cxve.verification.adapter.out.http;

/** A raw downstream response: status + body, no exceptions — callers decide what a 4xx means. */
public record HttpResult(int status, String body) {

    public boolean is2xx() {
        return status >= 200 && status < 300;
    }

    public boolean is4xx() {
        return status >= 400 && status < 500;
    }
}
