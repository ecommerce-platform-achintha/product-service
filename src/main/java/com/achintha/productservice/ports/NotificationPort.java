package com.achintha.productservice.ports;

import java.util.UUID;

/** Sends SMS / email / in-app notifications (section 10). Implementations must never log message secrets. */
public interface NotificationPort {

    /** Tells the store's team something (in-app record), e.g. a new product review or a variant running low. */
    void notifyStore(UUID storeId, String template, String subject);

    /** Alerts the admin team (in-app record), e.g. stock holds released by the safety-net scheduler. */
    void notifyAdmins(String template, String subject);
}
