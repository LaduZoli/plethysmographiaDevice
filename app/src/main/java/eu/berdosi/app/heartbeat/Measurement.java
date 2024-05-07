package eu.berdosi.app.heartbeat;

import java.util.Date;

public class Measurement<T> {
    final Date timestamp;
    final T measurement;

    final String userId;

    Measurement(Date timestamp, T measurement, String userId) {
        this.timestamp = timestamp;
        this.measurement = measurement;
        this.userId = userId;
    }
}
