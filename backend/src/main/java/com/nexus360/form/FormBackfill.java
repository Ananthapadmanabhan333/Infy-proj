package com.nexus360.form;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Catalogue items created without a form definition (Release 2 code paths such as the demo seeder) get their own form at
 * start-up — the runtime counterpart of the V7 migration of existing items. No-op when every item has a form.
 */
@Slf4j
@Component
@Order(25)
@RequiredArgsConstructor
public class FormBackfill implements ApplicationRunner {

    private final FormService forms;

    @Override
    public void run(ApplicationArguments args) {
        int n = forms.backfillItems();
        if (n > 0) {
            log.info("Form builder: {} catalogue item(s) given their own form definition", n);
        }
    }
}
