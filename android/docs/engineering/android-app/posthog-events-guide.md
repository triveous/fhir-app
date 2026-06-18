# PostHog Analytics Events Guide

This document provides a comprehensive list of all PostHog analytics events tracked within the FHIRCore Android application. This guide is intended for Product Managers and Data Analysts to understand when each event is triggered and what data is associated with it.

## 1. User Identification
The application identifies users using a hashed version of their Practitioner ID (`flwid`).

| Call | Location | Properties |
| :--- | :--- | :--- |
| `PostHog.identify` | `LoginViewModel`, `PinViewModel`, `RegisterViewModel`, `AppMainViewModel` | `flwid` (hashed), `app_version_code`, `app_version_name`, `pending_sync_images`, `pending_sync_cases` |

---

## 2. Screen Views
Tracks when a user navigates to a specific screen.

| Screen Name | Trigger (When) |
| :--- | :--- |
| `RegisterScreen` | When the main Patient Register list is viewed. |
| `DashboardScreen` | When the Health Center dashboard (summary view) is viewed. |
| `TasksScreen` | When the follow-up tasks/recommendations list is viewed. |
| `QuestionnaireActivity` | When any questionnaire (form) is opened. |
| `AIResultActivity` | When the AI screening results screen is displayed. |

---

## 3. Core Workflow Events

### Sync Events
| Event Name | Screen / Component | Trigger (When) | Properties |
| :--- | :--- | :--- | :--- |
| `sync_initiated` | Dashboard / Registers / Tasks | When the user taps the Sync button or a periodic sync starts. | - |
| `sync_completed` | AppMain (Background) | When the synchronization process finishes. | `sync_status` (succeeded/failed), `sync_duration_ms`, `pending_images_after`, `pending_cases_after`, `error_message` |

### Questionnaire Events
| Event Name | Screen / Component | Trigger (When) | Properties |
| :--- | :--- | :--- | :--- |
| `questionnaire_opened` | QuestionnaireActivity | When a questionnaire is launched. | `questionnaire_id`, `questionnaire_kind` (registration/followup/other), `screening_id` |
| `questionnaire_submitted` | QuestionnaireActivity | When the user submits a completed questionnaire. | `questionnaire_id`, `questionnaire_kind`, `screening_id`, `refer_case`, `ai_verdict`, `ai_overridden` |
| `questionnaire_draft_saved` | QuestionnaireActivity | When the user exits a form and chooses 'Save Draft'. | `questionnaire_id`, `questionnaire_kind`, `screening_id` |
| `questionnaire_draft_deleted` | RegisterScreen | When a user deletes a saved draft. | - |

### AI & Imaging Events
| Event Name | Screen / Component | Trigger (When) | Properties |
| :--- | :--- | :--- | :--- |
| `photo_captured` | Camera Launcher | When a photo is taken and confirmed. | `ai_prediction`, `ai_confidence`, `screening_id`, `time_to_capture_ms`, `capture_to_result_ms`, `blur_laplacian_variance`, `brightness_mean`, `contrast_stddev`, `noise_estimate`, `device_model`, `memory_available_mb`, etc. |
| `photo_retaken` | Camera Launcher | When the user chooses to retake a photo. | `screening_id`, `retake_index` |
| `ai_inference_completed` | QuestionnaireActivity | When all AI models finish processing. | `is_suspicious`, `image_count`, `suspicious_image_count`, `non_suspicious_image_count`, `low_confidence_image_count`, `mean_confidence`, `memory_used_mb`, etc. |
| `ai_result_viewed` | AIResultActivity | When the AI result screen is displayed. | `is_suspicious`, `screening_id` |
| `ai_refer_case` | AIResultActivity | When 'Refer Case' is clicked on result screen. | `is_suspicious`, `screening_id` |
| `model_inference_completed`| Camera Launcher | When an individual AI model finishes. | `model_name` (v6/v8/v82/ensemble), `model_version`, `model_prediction`, `model_confidence`, `model_entropy`, `low_confidence`, `inference_time_ms`, `combined_inference_time_ms` |
| `image_upload_completed` | Sync Worker | When an image is uploaded to server. | `document_id`, `upload_duration_ms`, `response_code`, `pending_documents`, `bytes_uploaded`, `error_message` |

### Task Management
| Event Name | Screen / Component | Trigger (When) | Properties |
| :--- | :--- | :--- | :--- |
| `task_status_updated` | Tasks Screen | When a user updates a task status. | `task_id`, `task_status`, `task_code`, `linked_questionnaire_id` |

---

## 4. Workflow Timing (Screening Flow)
Managed via `ScreeningTimer` to track session-level efficiency.

| Event Name | Trigger (When) | Properties |
| :--- | :--- | :--- |
| `screening_step_completed` | Milestone reached (e.g., photo_capture). | `screening_id`, `step_name`, `step_duration_ms`, `cumulative_duration_ms` |
| `screening_completed` | Process ends (Submit/Draft/Abandon). | `screening_id`, `total_duration_ms`, `outcome` (submitted/draft_saved/abandoned/refer_case), `photo_count`, `retake_count`, `battery_delta_pct` |
| `screening_abandoned` | User exits without saving or submitting. | (Same as `screening_completed`) |

---

## 5. System Health
| Event Name | Screen / Component | Trigger (When) | Properties |
| :--- | :--- | :--- | :--- |
| `error` | Various | When a handled system error occurs. | `error_source`, `error_message`, plus contextual info. |
