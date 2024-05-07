// ApiService.java
package eu.berdosi.app.heartbeat;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;


public interface ApiService {
    @POST("pulse")
    Call<Void> sendPulseData(@Body Measurement<Float> measurement);
}
