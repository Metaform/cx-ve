package com.metaform.cxve.verification.adapter.in.web;

import com.metaform.cxve.verification.application.DataspaceCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the UI offers before a run starts: the dataspaces, the use cases in each, the dataspace's
 * member-id format, and which of them can be verified right now.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final DataspaceCatalog catalog;

    public CatalogController(DataspaceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<DataspaceCatalog.Dataspace> list() {
        return catalog.list();
    }
}
