package com.rundeck.plugins

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Created by luistoledo on 6/28/17.
 */
interface PagerDutyApi {
    @POST("v2/enqueue")
    Call<PagerResponse> sendEvent(@Body LinkedHashMap json);
}