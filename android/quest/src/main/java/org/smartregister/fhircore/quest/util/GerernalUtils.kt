package org.smartregister.fhircore.quest.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.MutableLiveData
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.domain.model.ActionParameter

/**
 * Created by Jeetesh Surana.
 */

fun <T> mutableLiveData(defaultValue: T? = null): MutableLiveData<T> {
    val data = MutableLiveData<T>()
    defaultValue?.let { data.value = it }
    return data
}

fun languageExtension(languageCode: String): Extension {
    return Extension().apply {
        url = "http://hl7.org/fhir/StructureDefinition/translation"
        addExtension("url", StringType("http://hl7.org/fhir/StructureDefinition/translation"))
        addExtension("valueCode", StringType(languageCode))
        addExtension("language", StringType(languageCode))
    }
}

fun languageExtensionToActionParameters(languageCode: String): List<ActionParameter> {
    val extension = languageExtension(languageCode)

    return extension.extension.map { ext ->
        ActionParameter(
            key = ext.url,  // Using the extension URL as the key
            dataType = Enumerations.DataType.STRING,  // Assuming the data type is STRING
            value = ext.value.toString(),  // Convert value to String
            linkId = null,  // Assuming no linkId for this case
            resourceType = null  // Assuming no specific resourceType
        )
    }
}

fun extensionToMap(extension: Extension): HashMap<String, String> {
    val map = HashMap<String, String>()

    // Add the main extension URL
    map["url"] = extension.url

    // Iterate over nested extensions and add them to the map
    extension.extension.forEach {
        val key = it.url
        val value = it.value.toString()
        map[key] = value
    }

    return map
}

/**
 * Add replace fragment
 *
 * @param container
 * @param fragment
 * @param addFragment
 * @param addToBackStack
 */
fun FragmentActivity.addReplaceFragment(
    @IdRes container: Int,
    fragment: Fragment,
    addFragment: Boolean,
    addToBackStack: Boolean
) {
    val transaction: FragmentTransaction = supportFragmentManager.beginTransaction()
    if (addFragment) {
        transaction.add(
            container,
            fragment,
            fragment.javaClass.simpleName
        )
    } else {
        transaction.replace(
            container,
            fragment,
            fragment.javaClass.simpleName
        )
    }
    if (addToBackStack) {
        transaction.addToBackStack(fragment.tag)
    }
    hideKeyboard()
    if (!supportFragmentManager.isDestroyed) {
        transaction.commit()
    }
}


//hide the keyboard
fun Activity.hideKeyboard() {
    val imm: InputMethodManager =
        getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
    var view = currentFocus
    if (view == null) view = View(this)
    imm.hideSoftInputFromWindow(
        view.windowToken,
        0
    )
}
//fun Activity.restartApp() {
//    val intent = baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
//    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
//    startActivity(intent)
//    Runtime.getRuntime().exit(0)
//}
fun Context.restartApp() {
    val intent = packageManager.getLaunchIntentForPackage(packageName)
    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    Runtime.getRuntime().exit(0)
}