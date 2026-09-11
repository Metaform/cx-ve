package com.metaform.cxve.verification.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback: the Angular app is served from the packaged static resources with base href
 * {@code /ui/}; the gateway strips that prefix, so a deep link like {@code /ui/runs/x} arrives
 * here as {@code /runs/x} — forward it to index.html and let the Angular router restore the
 * view. Only the SPA's own route roots are forwarded; unknown paths still 404.
 */
@Controller
public class SpaForwardingController {

    @GetMapping({"/", "/runs/**"})
    public String index() {
        return "forward:/index.html";
    }
}
