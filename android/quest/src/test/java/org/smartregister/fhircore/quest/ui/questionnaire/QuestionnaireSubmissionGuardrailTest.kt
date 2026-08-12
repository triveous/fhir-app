/*
 * Copyright 2021-2026 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ui.questionnaire

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.context.FhirContext
import io.mockk.mockk
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig

/**
 * The guardrail that runs after extraction and before anything is written or synced.
 *
 * The data capture library blocks its own submit button on required fields, but it does that before
 * the app touches the response — and the app then keeps working on it, most notably by deleting
 * screening-image answers whose DocumentReference has gone missing. `patient-screening-image-1` is
 * required, so that path can empty a required answer after the only check that was watching.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [Build.VERSION_CODES.Q])
class QuestionnaireSubmissionGuardrailTest {

    private lateinit var viewModel: QuestionnaireViewModel

    @Before
    fun setUp() {
        viewModel =
            QuestionnaireViewModel(
                defaultRepository = mockk(relaxed = true),
                dispatcherProvider = mockk(relaxed = true),
                fhirCarePlanGenerator = mockk(relaxed = true),
                resourceDataRulesExecutor = mockk(relaxed = true),
                transformSupportServices = mockk(relaxed = true),
                sharedPreferencesHelper = mockk(relaxed = true),
                secureSharedPreference = mockk(relaxed = true),
                fhirOperator = mockk(relaxed = true),
                fhirPathDataExtractor = mockk(relaxed = true),
                configurationRegistry = mockk(relaxed = true),
                syncBroadcaster = mockk(relaxed = true),
                fhirEngine = mockk(relaxed = true),
                uploadedDocumentReferenceLedger = mockk(relaxed = true),
            )
    }

    @Test
    fun `a fully answered registration is not blocked`() {
        val missing = viewModel.findUnansweredRequiredQuestions(registration(), completedResponse())

        assertTrue("A complete form must submit: $missing", missing.isEmpty())
    }

    /** The DocumentReference reconciliation empties this answer, after the library has signed off. */
    @Test
    fun `an image answer emptied after the library validated is caught`() {
        val response =
            completedResponse().apply {
                itemAt("screening-group", "patient-screening-image-1").answer = emptyList()
            }

        val missing = viewModel.findUnansweredRequiredQuestions(registration(), response)

        assertEquals(listOf("patient-screening-image-1"), missing.map { it.linkId })
        assertEquals("Image 1", missing.single().label)
    }

    @Test
    fun `a required question dropped from the response entirely is caught`() {
        val response =
            completedResponse().apply {
                val group = item.first { it.linkId == "basic-info-group" }
                group.item.removeIf { it.linkId == "patient-name-given" }
            }

        val missing = viewModel.findUnansweredRequiredQuestions(registration(), response)

        assertEquals(listOf("patient-name-given"), missing.map { it.linkId })
    }

    /**
     * The library's own `RequiredValidator` is satisfied by `answer.any { it.hasValue() }`, so all
     * three of these count as answered there. None of them is anything a person typed.
     */
    @Test
    fun `blank and hollow answers do not count as answers`() {
        val blanks: List<Pair<String, Type>> =
            listOf(
                "patient-name-given" to StringType("   "),
                "patient-gender" to Coding(),
                "patient-screening-image-1" to Attachment(),
            )

        blanks.forEach { (linkId, hollowValue) ->
            val response =
                completedResponse().apply {
                    allItems().first { it.linkId == linkId }.answer =
                        mutableListOf(
                            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                                value = hollowValue
                            },
                        )
                }

            val missing = viewModel.findUnansweredRequiredQuestions(registration(), response)

            assertEquals(
                "$linkId with a hollow ${hollowValue.fhirType()} answer should be reported",
                listOf(linkId),
                missing.map { it.linkId },
            )
        }
    }

    /** `false` is an answer; a checkbox the user deliberately left off must not block them. */
    @Test
    fun `a false boolean answer counts as answered`() {
        val questionnaire =
            registration().apply {
                item
                    .first { it.linkId == "basic-info-group" }
                    .addItem(
                        Questionnaire.QuestionnaireItemComponent().apply {
                            linkId = "patient-consent"
                            type = Questionnaire.QuestionnaireItemType.BOOLEAN
                            required = true
                        },
                    )
            }
        val response =
            completedResponse().apply {
                item
                    .first { it.linkId == "basic-info-group" }
                    .addItem(answer("patient-consent", BooleanType(false)))
            }

        assertTrue(viewModel.findUnansweredRequiredQuestions(questionnaire, response).isEmpty())
    }

    /**
     * `QuestionnaireFragment.getQuestionnaireResponse()` strips disabled items, so an item that
     * `enableWhen` can switch off is legitimately absent — reporting it would block a user who was
     * never asked the question.
     */
    @Test
    fun `a required question disabled by enableWhen is not reported when absent`() {
        val questionnaire =
            registration().apply {
                item
                    .first { it.linkId == "basic-info-group" }
                    .addItem(
                        Questionnaire.QuestionnaireItemComponent().apply {
                            linkId = "patient-age-by-dob"
                            type = Questionnaire.QuestionnaireItemType.DATE
                            required = true
                            addEnableWhen(
                                Questionnaire.QuestionnaireItemEnableWhenComponent().apply {
                                    question = "patient-age"
                                    operator = Questionnaire.QuestionnaireItemOperator.EQUAL
                                    answer = Coding().setCode("dob")
                                },
                            )
                        },
                    )
            }

        assertTrue(
            viewModel.findUnansweredRequiredQuestions(questionnaire, completedResponse()).isEmpty(),
        )
    }

    /** Hidden items are populated by the StructureMap, not by the user. */
    @Test
    fun `a hidden required question is not reported`() {
        val questionnaire =
            registration().apply {
                item
                    .first { it.linkId == "basic-info-group" }
                    .addItem(
                        Questionnaire.QuestionnaireItemComponent().apply {
                            linkId = "patient-record-id"
                            type = Questionnaire.QuestionnaireItemType.STRING
                            required = true
                            addExtension(
                                Extension(
                                    "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden",
                                    BooleanType(true),
                                ),
                            )
                        },
                    )
            }

        assertTrue(
            viewModel.findUnansweredRequiredQuestions(questionnaire, completedResponse()).isEmpty(),
        )
    }

    /** Every required question is named, so the user fixes the form in one pass rather than three. */
    @Test
    fun `all unanswered required questions are reported together`() {
        val response =
            completedResponse().apply {
                allItems().first { it.linkId == "patient-name-given" }.answer = emptyList()
                allItems().first { it.linkId == "patient-gender" }.answer = emptyList()
                allItems().first { it.linkId == "patient-screening-image-1" }.answer = emptyList()
            }

        val missing = viewModel.findUnansweredRequiredQuestions(registration(), response)

        assertEquals(
            listOf("patient-name-given", "patient-gender", "patient-screening-image-1"),
            missing.map { it.linkId },
        )
    }

    @Test
    fun `optional questions never block a submission`() {
        val response =
            completedResponse().apply {
                allItems().first { it.linkId == "patient-address-house" }.answer = emptyList()
            }

        assertTrue(viewModel.findUnansweredRequiredQuestions(registration(), response).isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // the real shipped form
    // ---------------------------------------------------------------------------------------

    /**
     * A false positive here blocks an FLW who has filled the form in correctly, so the guardrail is
     * exercised against the questionnaire the app actually ships — 40 items over four groups, 19 of
     * them required, 13 hidden AI-result items and the mutually exclusive age pair.
     */
    @Test
    fun `a completed response to the shipped registration form reports nothing`() {
        val questionnaire = shippedRegistrationForm()

        val missing =
            viewModel.findUnansweredRequiredQuestions(
                questionnaire,
                answerEveryEnabledRequiredQuestion(questionnaire),
            )

        assertTrue("The shipped form must not block a completed response: $missing", missing.isEmpty())
    }

    @Test
    fun `emptying one answer on the shipped form is caught`() {
        val questionnaire = shippedRegistrationForm()
        val response =
            answerEveryEnabledRequiredQuestion(questionnaire).apply {
                allItems().first { it.linkId == "patient-contact-primary" }.answer = emptyList()
            }

        val missing = viewModel.findUnansweredRequiredQuestions(questionnaire, response)

        assertEquals(listOf("patient-contact-primary"), missing.map { it.linkId })
    }

    /** Guards the required set itself: adding a required question to the form should be a decision. */
    @Test
    fun `the shipped form's required questions are the expected ones`() {
        val questionnaire = shippedRegistrationForm()
        val response =
            QuestionnaireResponse().apply {
                // Mirrors the form's groups with no answers at all, so every enabled required
                // question is reported.
                questionnaire.item.forEach { addItem(mirrorGroups(it)) }
            }

        val reported = viewModel.findUnansweredRequiredQuestions(questionnaire, response)

        assertEquals(
            listOf(
                "location-physical-type",
                "patient-name-given",
                "patient-name-family",
                "patient-age",
                "patient-gender",
                "patient-contact-primary",
                "patient-address-village",
                "patient-address-state",
                "patient-address-district",
                "patient-habit-cigarette",
                "patient-habit-tobacco",
                "patient-habit-areca",
                "patient-habit-alcohol",
                "patient-screening-mouth-open",
                "patient-screening-lesion",
                "patient-screening-self-reported",
                "patient-screening-image-1",
            ),
            reported.map { it.linkId },
        )
    }

    private fun shippedRegistrationForm(): Questionnaire =
        ApplicationProvider.getApplicationContext<Application>()
            .assets
            .open("sample_patient_registration.json")
            .bufferedReader()
            .use { it.readText() }
            .let { FhirContext.forR4Cached().newJsonParser().parseResource(it) as Questionnaire }

    /**
     * Builds the response the fragment would hand over for a fully completed form: the group
     * structure mirrored, every enabled required question answered, and — as
     * `getQuestionnaireResponse()` does — items switched off by `enableWhen` left out entirely.
     */
    private fun answerEveryEnabledRequiredQuestion(questionnaire: Questionnaire) =
        QuestionnaireResponse().apply {
            questionnaire.item.forEach { addItem(mirror(it)) }
        }

    private fun mirror(
        item: Questionnaire.QuestionnaireItemComponent,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = item.linkId
            item.item.filterNot { it.hasEnableWhen() }.forEach { addItem(mirror(it)) }
            if (item.required) {
                addAnswer(
                    QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                        value = sampleAnswerFor(item.type)
                    },
                )
            }
        }

    /** Mirrors the group skeleton only, leaving every question unanswered. */
    private fun mirrorGroups(
        item: Questionnaire.QuestionnaireItemComponent,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = item.linkId
            item.item.filterNot { it.hasEnableWhen() }.forEach { addItem(mirrorGroups(it)) }
        }

    private fun sampleAnswerFor(type: Questionnaire.QuestionnaireItemType): Type =
        when (type) {
            Questionnaire.QuestionnaireItemType.CHOICE -> Coding().setCode("yes").setDisplay("Yes")
            Questionnaire.QuestionnaireItemType.ATTACHMENT ->
                Attachment().apply { url = "https://example.org/fhir/DocumentReference/doc-1/x" }
            Questionnaire.QuestionnaireItemType.INTEGER -> IntegerType(44)
            Questionnaire.QuestionnaireItemType.DATE -> DateType("1982-06-22")
            Questionnaire.QuestionnaireItemType.BOOLEAN -> BooleanType(true)
            else -> StringType("answered")
        }

    // ---------------------------------------------------------------------------------------
    // extractionProducedNothingUsable
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an extraction that produced a populated patient is usable`() {
        val bundle = bundleOf(populatedPatient(), Observation())

        assertFalse(
            viewModel.extractionProducedNothingUsable(
                bundle = bundle,
                questionnaire = registration(),
                questionnaireConfig = registrationConfig(),
                questionnaireResponse = completedResponse(),
            ),
        )
    }

    @Test
    fun `an empty bundle blocks a fresh registration`() {
        assertTrue(
            viewModel.extractionProducedNothingUsable(
                bundle = Bundle(),
                questionnaire = registration(),
                questionnaireConfig = registrationConfig(),
                questionnaireResponse = completedResponse(),
            ),
        )
    }

    /** A StructureMap that runs but maps nothing: a valid-looking case that opens blank. */
    @Test
    fun `a patient husk carrying only bookkeeping blocks the submission`() {
        val husk =
            Patient().apply {
                id = "patient-1"
                addIdentifier(Identifier().setValue("46078090"))
            }

        assertTrue(
            viewModel.extractionProducedNothingUsable(
                bundle = bundleOf(husk),
                questionnaire = registration(),
                questionnaireConfig = registrationConfig(),
                questionnaireResponse = completedResponse(),
            ),
        )
    }

    @Test
    fun `a bundle with no subject resource blocks a fresh registration`() {
        assertTrue(
            viewModel.extractionProducedNothingUsable(
                bundle = bundleOf(Observation().apply { id = "obs-1" }),
                questionnaire = registration(),
                questionnaireConfig = registrationConfig(),
                questionnaireResponse = completedResponse(),
            ),
        )
    }

    /**
     * An edit or follow-up already has its subject and extracts Observations and Encounters against
     * it, so judging it on a Patient it was never going to produce would block every one of them.
     */
    @Test
    fun `an edit that extracts no patient is left alone`() {
        val response = completedResponse().apply { subject = Reference("Patient/patient-1") }

        assertFalse(
            viewModel.extractionProducedNothingUsable(
                bundle = bundleOf(Observation().apply { id = "obs-1" }),
                questionnaire = registration(),
                questionnaireConfig = registrationConfig(),
                questionnaireResponse = response,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------

    private fun registrationConfig() =
        QuestionnaireConfig(id = "OralCancerPatientRegistration", resourceType = ResourceType.Patient)

    /** A cut-down copy of the shipped registration form, keeping its groups and required flags. */
    private fun registration() =
        Questionnaire().apply {
            addSubjectType("Patient")
            addItem(
                group("basic-info-group") {
                    addItem(question("patient-name-given", "First name", required = true))
                    addItem(question("patient-name-family", "Last name", required = true))
                    addItem(
                        question(
                            "patient-age",
                            "Age",
                            required = true,
                            type = Questionnaire.QuestionnaireItemType.CHOICE,
                        ),
                    )
                    addItem(
                        question(
                            "patient-gender",
                            "Gender",
                            required = true,
                            type = Questionnaire.QuestionnaireItemType.CHOICE,
                        ),
                    )
                    addItem(question("patient-address-house", "House number & street"))
                },
            )
            addItem(
                group("screening-group") {
                    addItem(
                        group("patient-screening-image-group") {
                            addItem(
                                question(
                                    "patient-screening-image-1",
                                    "Image 1",
                                    required = true,
                                    type = Questionnaire.QuestionnaireItemType.ATTACHMENT,
                                ),
                            )
                            addItem(
                                question(
                                    "patient-screening-image-2",
                                    "Image 2",
                                    type = Questionnaire.QuestionnaireItemType.ATTACHMENT,
                                ),
                            )
                        },
                    )
                },
            )
        }

    private fun completedResponse() =
        QuestionnaireResponse().apply {
            addItem(
                responseGroup("basic-info-group") {
                    addItem(answer("patient-name-given", StringType("IDPROBE")))
                    addItem(answer("patient-name-family", StringType("DRAFTFIX")))
                    addItem(answer("patient-age", Coding().setCode("years").setDisplay("By years")))
                    addItem(answer("patient-gender", Coding().setCode("male").setDisplay("Male")))
                    addItem(answer("patient-address-house", StringType("12B")))
                },
            )
            addItem(
                responseGroup("screening-group") {
                    addItem(
                        responseGroup("patient-screening-image-group") {
                            addItem(
                                answer(
                                    "patient-screening-image-1",
                                    Attachment().apply {
                                        contentType = "image/jpeg"
                                        url = "https://example.org/fhir/DocumentReference/doc-1/\$binary-access-read"
                                    },
                                ),
                            )
                            addItem(
                                QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                                    linkId = "patient-screening-image-2"
                                },
                            )
                        },
                    )
                },
            )
        }

    private fun group(
        linkId: String,
        build: Questionnaire.QuestionnaireItemComponent.() -> Unit,
    ) =
        Questionnaire.QuestionnaireItemComponent().apply {
            this.linkId = linkId
            type = Questionnaire.QuestionnaireItemType.GROUP
            build()
        }

    private fun question(
        linkId: String,
        text: String,
        required: Boolean = false,
        type: Questionnaire.QuestionnaireItemType = Questionnaire.QuestionnaireItemType.STRING,
    ) =
        Questionnaire.QuestionnaireItemComponent().apply {
            this.linkId = linkId
            this.text = text
            this.required = required
            this.type = type
        }

    private fun responseGroup(
        linkId: String,
        build: QuestionnaireResponse.QuestionnaireResponseItemComponent.() -> Unit,
    ) =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            build()
        }

    private fun answer(linkId: String, value: Type) =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            addAnswer(
                QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                    this.value = value
                },
            )
        }

    private fun QuestionnaireResponse.allItems():
        List<QuestionnaireResponse.QuestionnaireResponseItemComponent> {
        val all = mutableListOf<QuestionnaireResponse.QuestionnaireResponseItemComponent>()
        fun walk(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach {
                all.add(it)
                walk(it.item)
            }
        }
        walk(item)
        return all
    }

    private fun QuestionnaireResponse.itemAt(vararg path: String) =
        allItems().first { it.linkId == path.last() }

    private fun populatedPatient() =
        Patient().apply {
            id = "patient-1"
            addName(HumanName().addGiven("IDPROBE").setFamily("DRAFTFIX"))
            gender = Enumerations.AdministrativeGender.MALE
        }

    private fun bundleOf(vararg resources: org.hl7.fhir.r4.model.Resource) =
        Bundle().apply {
            resources.forEach { addEntry(Bundle.BundleEntryComponent().apply { resource = it }) }
        }
}
