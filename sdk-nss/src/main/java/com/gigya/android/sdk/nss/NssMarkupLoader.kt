@file:Suppress("KotlinConstantConditions")

package com.gigya.android.sdk.nss

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.gigya.android.sdk.GigyaCallback
import com.gigya.android.sdk.GigyaLogger
import com.gigya.android.sdk.account.models.GigyaAccount
import com.gigya.android.sdk.api.GigyaApiResponse
import com.gigya.android.sdk.api.IBusinessApiService
import com.gigya.android.sdk.network.GigyaError
import com.gigya.android.sdk.network.adapter.RestAdapter.POST
import com.gigya.android.sdk.nss.utils.NssJsonDeserializer
import com.gigya.android.sdk.nss.utils.guard
import com.gigya.android.sdk.nss.utils.refined
import com.gigya.android.sdk.nss.utils.serialize
import com.gigya.android.sdk.reporting.ReportingManager
import com.gigya.android.sdk.utils.FileUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.lang.reflect.Type


class NssMarkupLoader<T : GigyaAccount>(
    private val context: Context,
    private val api: IBusinessApiService<T>
) {

    companion object {
        const val LOG_TAG = "NssMarkupLoader"

        const val THEME_SUFFIX = ".theme.json"
        const val LOCALIZATION_SUFFIX = ".i18n.json"
    }

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(object : TypeToken<Map<String, Any>>() {}.type, NssJsonDeserializer())
        .create()

    /**
     * Load markup file from assets folder given filename/path.
     * @param context Application/Available context.
     * @param fileName Asset file name.
     */
    private fun loadJsonFromAssets(context: Context, fileName: String) = try {
        GigyaLogger.debug(Nss.LOG_TAG, "loadJsonFromAssets() with fileName $fileName.json")
        FileUtils.assetJsonFileToString(context, fileName)
    } catch (ioException: IOException) {
        ioException.printStackTrace()
        ReportingManager.get().error(GigyaNss.VERSION, "nss", "Failed to load markup asset")
        null
    }

    /**
     * Load markup from internal asset file.
     */
    @Suppress("UNCHECKED_CAST")
    fun loadMarkupAsset(
        assetPath: String?,
        initialRoute: String?,
        lang: String?
    ): MutableMap<String, Any>? {
        assetPath?.apply {
            val jsonAsset = loadJsonFromAssets(context, "$assetPath.json")
            jsonAsset.guard {
                GigyaLogger.error(LOG_TAG, "Failed to parse JSON asset")
                throw RuntimeException("Failed to parse JSON File from assets folder")
            }

            // Load optional asset/localization files.
            val themeAsset = loadJsonFromAssets(context, "$assetPath$THEME_SUFFIX")
            val localizationAsset = loadJsonFromAssets(context, "$assetPath$LOCALIZATION_SUFFIX")

            // Map available assets.
            val jsonMap = gson.fromJson<MutableMap<String, Any>>(
                jsonAsset,
                object : TypeToken<Map<String, Any>>() {}.type
            )
            jsonMap.refined<MutableMap<String, Any>> { map ->
                val routingMap: MutableMap<String, Any> = map["routing"] as MutableMap<String, Any>
                initialRoute?.let { userDefinedInitialRoute ->
                    routingMap["initial"] = userDefinedInitialRoute
                }
                if (!routingMap.containsKey("initial")) {
                    throw RuntimeException("Markup scheme incorrect - initial route must be provided")
                }

                // Add optional theme map.
                themeAsset?.let {
                    val themeMap = it.serialize<String, Any>(gson)
                    GigyaLogger.debug(Nss.LOG_TAG, "Adding parsed theme map to JSON markup")
                    map["theme"] = themeMap["theme"] as Map<String, Any>
                    map["customThemes"] = themeMap["customThemes"] as Map<String, Any>
                }

                // Add optional localization map.
                localizationAsset?.let {
                    val localMap = it.serialize<String, Any>(gson)
                    GigyaLogger.debug(Nss.LOG_TAG, "Adding parsed localization map to JSON markup")
                    map["i18n"] = localMap
                    // Add default language is relevant only when an additional localization JSON map as been provided.
                    lang?.let { localizationLanguage ->
                        map["lang"] = localizationLanguage
                    }
                }
            }

            return jsonMap

        } ?: GigyaLogger.error(LOG_TAG, "Provided markup asset not available")

        return null
    }

    /**
     * Load the markup from the remote API-Key host.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadMarkupRemote(
        screenSetId: String?,
        lang: String,
        onLoad: (MutableMap<String, Any>?) -> Unit,
        onLoadError: (GigyaError) -> Unit
    ) {
        screenSetId?.guard {
            throw RuntimeException("ScreenSet ID not provided - Flow invalid")
        }
        val params = mutableMapOf<String, Any>("screenSetId" to screenSetId!!, "lang" to lang)
        api.send("accounts.getNativeScreenSet", params, POST, GigyaApiResponse::class.java,
            object : GigyaCallback<GigyaApiResponse>() {

                override fun onSuccess(obj: GigyaApiResponse?) {
                    obj?.let { response ->
                        val markupMap = mutableMapOf<String, Any>()
                        markupMap["lang"] = lang
                        markupMap.putAll(response.asMap()["screenSet"] as MutableMap<out String, Any>)
                        onLoad(markupMap)
                    }
                }

                override fun onError(error: GigyaError?) {
                    error?.let {
                        onLoadError(it)
                    }
                }

            })
    }

    /**
     * Load markup.
     */
    fun loadMarkupFrom(
        data: IgnitionData,
        markupLoaded: (Map<String, Any>?) -> Unit,
        markupFailedToLoad: (GigyaError) -> Unit
    ) {
        if (data.screenSetId != null) {
            loadMarkupRemote(
                data.screenSetId,
                data.lang!!,
                { markupMap ->
                    addPlatformSettings(markupMap)
                    updateInitialRoute(markupMap, data.initialRoute)
                    markupLoaded(markupMap)
                },
                markupFailedToLoad
            )
        } else if (data.asset != null) {
            val markupMap = loadMarkupAsset(data.asset, data.initialRoute, data.lang!!)
            markupMap?.let { map ->
                addPlatformSettings(map)
                markupLoaded(map)
            } ?: markupFailedToLoad(GigyaError.errorFrom("Failed to load markup asset"));
        }
    }

    /**
     * Override initial route from hosted screen-set request if supplied by the user.
     */
    private fun updateInitialRoute(map: MutableMap<String, Any>?, initialRoute: String?) {
        if (map == null || initialRoute == null) return
        if (map.containsKey("routing")) {
            (map["routing"] as MutableMap<String, Any>)["initial"] = initialRoute
        }
    }

    /**
     * Add platform specific settings to the markup.
     *
     * Currently supported:
     * 1. iso3116-2 Country code taken from device network.
     */
    private fun addPlatformSettings(map: MutableMap<String, Any>?) {
        map?.let { markup ->
            val platform = mutableMapOf<String, Any>()
            val countryIso = detectNetworkCountry()
            if (countryIso != null) {
                platform["iso3166"] = countryIso
            }
            // Add to main markup only if has relevant data.
            if (platform.isNotEmpty()) {
                markup["platform"] = platform
            }
        }
    }

    /**
     * Reference device network locale.
     */
    private fun detectNetworkCountry(): String? {
        try {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            Log.d(LOG_TAG, "detectNetworkCountry: ${telephonyManager.simCountryIso}")
            return telephonyManager.networkCountryIso
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun getStyleLibraryData(
        screenSetId: String?,
        onStylesLoaded: (Map<String, Any>) -> Unit,
        onLoadError: (GigyaError) -> Unit
    ) {
        if (screenSetId == null) {
            onStylesLoaded(mapOf())
            return
        }
        api.send("accounts.getNSSTheme",
            mapOf(
                "themeId" to "system-default",
                "screenSetId" to screenSetId
            ),
            POST, GigyaApiResponse::class.java,
            object : GigyaCallback<GigyaApiResponse>() {
                override fun onSuccess(obj: GigyaApiResponse?) {
                    if (obj != null) {
                        var styles = mutableMapOf<String, Any>()
                        val stylesJson = obj.asMap()["theme"] as String
                        val mapType: Type = object : TypeToken<MutableMap<String, Any>>() {}.type
                        styles = gson.fromJson(stylesJson, mapType)
                        onStylesLoaded(styles)
                    }
                }

                override fun onError(error: GigyaError?) {
                    error?.let {
                        onLoadError(it)
                    }
                }

            })

    }
}