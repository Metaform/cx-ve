package com.metaform.cxve.hub.adapter.in.web;

import com.metaform.cxve.hub.application.DataspaceRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The dataspaces members can be onboarded into — the valid values of a request's {@code dataspace}. */
@RestController
@RequestMapping("/api/dataspaces")
public class DataspaceController {

    private final DataspaceRegistry registry;

    public DataspaceController(DataspaceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<DataspaceRegistry.ServedDataspace> list() {
        return registry.served();
    }
}
