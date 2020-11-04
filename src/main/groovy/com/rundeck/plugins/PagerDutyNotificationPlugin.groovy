package com.rundeck.plugins

import com.dtolabs.rundeck.core.plugins.Plugin
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty
import com.dtolabs.rundeck.plugins.descriptions.SelectValues
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


/**
 * Raise PagerDuty alert using the v2 API
 * 
 * Although Rundeck provide a plugin, the free version does not
 * support a unique service key per job, which we require.
 * Forked from https://github.com/rundeck-plugins/pagerduty-notification
 * 
 * Chris Gadd
 * 2020-10-28
 */


@Plugin(service="Notification", name="PagerDutyNotification")
@PluginDescription(title="PagerDuty", description="Create a PagerDuty event.")
public class PagerDutyNotificationPlugin implements NotificationPlugin {

    final static String PAGERDUTY_URL = "https://events.pagerduty.com"
    final static String SUBJECT_LINE='${job.status} [${job.project}] \"${job.name}\" run by ${job.user} (#${job.execid}) [ ${job.href} ]'

    @PluginProperty(title = "subject", description = "Incident subject line", required = false, defaultValue = PagerDutyNotificationPlugin.SUBJECT_LINE)
    private String subject;

    @PluginProperty(title = "Integration Key", description = "Integration Key. If not provided will default to your team's integration key (which must have been setup prior).", required = true, scope = PropertyScope.Instance)
    private String integration_key;

    @PluginProperty(title = "Severity", description = "Alert severity", required = true, defaultValue = "Error")
    @SelectValues(freeSelect = false, values = ["Critical", "Error", "Warning", "Info"])
    private String severity;

    @PluginProperty(title = "Action", description = "Alert action. Trigger to raise an alert, Resolve to clear.", required = true, defaultValue = "Trigger")
    @SelectValues(freeSelect = false, values = ["Trigger", "Resolve"])
    private String status;

    @PluginProperty(title = "Proxy host", description = "Outbound proxy host", required = false, scope = PropertyScope.Framework)
    private String proxy_host;

    @PluginProperty(title = "Proxy port", description = "Outbound proxy port", required = false, scope = PropertyScope.Framework)
    private String proxy_port;


    @Override
    public boolean postNotification(String trigger, Map executionData, Map config) {
        triggerEvent(trigger, executionData, config)
        true
    }

    /**
     * Trigger a pager duty incident.
     * @param executionData
     * @param configuration
     */
    def triggerEvent(String trigger, Map executionData, Map configuration) {
        if (proxy_host != null && proxy_port != null) {
            System.err.println("DEBUG: proxy_host="+proxy_host)
            System.err.println("DEBUG: proxy_port="+proxy_port)
            System.getProperties().put("proxySet", "true")
            System.getProperties().put("proxyHost", proxy_host)
            System.getProperties().put("proxyPort", proxy_port)
        }

         Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(PAGERDUTY_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        PagerDutyApi apiService = retrofit.create(PagerDutyApi.class)

        callAPI(trigger,apiService, executionData)
    }


    /**
     * Expands the Subject string using a predefined set of tokens
     */
    def subjectString(text,binding) {
        //defines the set of tokens usable in the subject configuration property
        def tokens=[
                '${job.status}': binding.execution.status.toUpperCase(),
                '${job.project}': binding.execution.job.project,
                '${job.name}': binding.execution.job.name,
                '${job.group}': binding.execution.job.group,
                '${job.user}': binding.execution.user,
                '${job.href}': binding.execution.href,
                '${job.execid}': binding.execution.id.toString()
        ]
        text.replaceAll(/(\$\{\S+?\})/){
            if(tokens[it[1]]){
                tokens[it[1]]
            } else {
                it[0]
            }
        }
    }


    def callAPI(String trigger,PagerDutyApi apiService, Map executionData){
        def expandedSubject = subjectString(subject, [execution:executionData])

        def date
        if (trigger=="start" || trigger=="avgduration"){
            date = executionData.dateStartedW3c
        }else{
            date = executionData.dateEndedW3c
        }

        def host = InetAddress.getLocalHost().getHostName()

        def job_data = [
            event_action: status.toLowerCase(),
            routing_key: integration_key,
            dedup_key: executionData.job.id,
            payload: [
                    summary: expandedSubject,
                    source: "Rundeck on " + host,
                    severity: severity.toLowerCase(),
                    timestamp: date,
                    group: executionData.job.name,
                    custom_details:[job: executionData.job.group + "/" + executionData.job.name,
                             description: executionData.job.description,
                             project: executionData.job.project,
                             user: executionData.user,
                             status: executionData.status,
                             trigger: trigger
                    ]
            ],
            links:[
                    [href: executionData.href, text: "Execution Link"],
                    [href: executionData.job.href, text: "Job Link"],
            ]

        ]

        Response<PagerResponse> response = apiService.sendEvent(job_data).execute()
        if(response.errorBody()!=null){
            println "Error body:" + response.errorBody().string()
        }else{
            println("DEBUG: response: "+response)
        }
    }
}
