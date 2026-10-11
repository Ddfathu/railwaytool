package com.example.railwaymanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class RailwayApiClient(private val token: String) {
    private val client = OkHttpClient()
    private val graphqlUrl = "https://backboard.railway.com/graphql/v2"

    private suspend fun postGraphql(query: String, variables: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }

        val request = Request.Builder()
            .url(graphqlUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: "{}"
            JSONObject(raw)
        }
    }

    suspend fun getServices(): List<ServiceItem> {
        val query = """
            query {
              me {
                projects {
                  edges {
                    node {
                      id name
                      environments { edges { node { id name } } }
                      services { edges { node { id name } } }
                    }
                  }
                }
                workspaces {
                  projects {
                    edges {
                      node {
                        id name
                        environments { edges { node { id name } } }
                        services { edges { node { id name } } }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val json = postGraphql(query)
        val data = json.optJSONObject("data") ?: return emptyList()
        val me = data.optJSONObject("me") ?: return emptyList()

        val list = mutableListOf<ServiceItem>()

        fun parseEdges(projectsArr: org.json.JSONArray?) {
            if (projectsArr == null) return
            for (i in 0 until projectsArr.length()) {
                val prj = projectsArr.getJSONObject(i).getJSONObject("node")
                val prjName = prj.getString("name")
                val envId = prj.getJSONObject("environments")
                    .getJSONArray("edges").optJSONObject(0)?.getJSONObject("node")?.getString("id") ?: continue

                val srvEdges = prj.getJSONObject("services").getJSONArray("edges")
                for (j in 0 until srvEdges.length()) {
                    val srv = srvEdges.getJSONObject(j).getJSONObject("node")
                    list.add(ServiceItem(prjName, srv.getString("name"), srv.getString("id"), envId))
                }
            }
        }

        parseEdges(me.optJSONObject("projects")?.optJSONArray("edges"))
        val workspaces = me.optJSONArray("workspaces")
        if (workspaces != null) {
            for (i in 0 until workspaces.length()) {
                parseEdges(workspaces.getJSONObject(i).optJSONObject("projects")?.optJSONArray("edges"))
            }
        }
        return list
    }

    suspend fun deployZipFile(file: File, serviceId: String, environmentId: String): Boolean = withContext(Dispatchers.IO) {
        val mutation = """
            mutation getUploadUrl(${"$"}environmentId: String!, ${"$"}serviceId: String!) {
              serviceDeployPayload(environmentId: ${"$"}environmentId, serviceId: ${"$"}serviceId) {
                uploadUrl
              }
            }
        """.trimIndent()

        val vars = JSONObject().apply {
            put("environmentId", environmentId)
            put("serviceId", serviceId)
        }

        val res = postGraphql(mutation, vars)
        val uploadUrl = res.optJSONObject("data")
            ?.optJSONObject("serviceDeployPayload")
            ?.optString("uploadUrl") ?: return@withContext false

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .put(file.asRequestBody("application/x-zip-compressed".toMediaType()))
            .build()

        client.newCall(uploadReq).execute().use { putRes ->
            putRes.isSuccessful
        }
    }
}

data class ServiceItem(
    val projectName: String,
    val serviceName: String,
    val serviceId: String,
    val environmentId: String
)
