---
title: Data Export
sidebar_label: Data Export
---

# Data Export Documentation for Aarogya Aarohan

This document describes how to extract non-PII (Personally Identifiable Information) data from Aarogya Aarohan in standard formats, enabling data analysis and integration with other systems while maintaining privacy and security.

## Overview

Aarogya Aarohan provides multiple methods for exporting data in standard formats, ensuring that users can access and analyze healthcare data without compromising patient privacy. All export mechanisms comply with healthcare data regulations and privacy standards.

## Export Methods

### 1. FHIR REST APIs

The primary method for data export is through FHIR REST APIs, which provide standardized access to healthcare data.

#### Base URL Configuration
```bash
# FHIR server base URL
FHIR_BASE_URL=https://your-fhir-server.com/fhir/
```

#### Authentication
```bash
# OAuth 2.0 authentication
curl -X POST "https://keycloak-server/auth/realms/your-realm/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=your-client-id" \
  -d "client_secret=your-client-secret"
```

#### Export Endpoints

**Questionnaire Responses:**
```bash
# Get all questionnaire responses
GET /QuestionnaireResponse

# Get responses for specific questionnaire
GET /QuestionnaireResponse?questionnaire=Questionnaire/your-questionnaire-id

# Get responses within date range
GET /QuestionnaireResponse?date=ge2023-01-01&date=le2023-12-31
```

**Observations:**
```bash
# Get all observations
GET /Observation

# Get observations by category
GET /Observation?category=oral-cancer-screening

# Get observations by patient (with proper authorization)
GET /Observation?subject=Patient/patient-id
```

**Patients (Anonymized):**
```bash
# Get patient demographics (anonymized)
GET /Patient?_count=100&_sort=-_lastUpdated
```

### 2. Bulk Export

For large-scale data export, the system supports FHIR Bulk Export operations.

#### Initiate Bulk Export
```bash
# Start bulk export job
POST /$export
Content-Type: application/fhir+json

{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "_outputFormat",
      "valueString": "application/fhir+ndjson"
    },
    {
      "name": "_since",
      "valueDateTime": "2023-01-01T00:00:00Z"
    },
    {
      "name": "_type",
      "valueString": "QuestionnaireResponse,Observation,Patient"
    }
  ]
}
```

#### Monitor Export Status
```bash
# Check export job status
GET /$export-poll-status?job=export-job-id
```

#### Download Export Files
```bash
# Download exported data
GET /$export-poll-status?job=export-job-id
# Response includes download URLs for exported files
```

### 3. CSV Export

The application provides CSV export functionality for specific data types.

#### Export Questionnaire Responses
```bash
# Export questionnaire responses to CSV
GET /QuestionnaireResponse?_format=csv&_count=1000
```

#### Export Observations
```bash
# Export observations to CSV
GET /Observation?_format=csv&category=oral-cancer-screening
```

### 4. JSON Export

Standard JSON export is available for all FHIR resources.

```bash
# Export in JSON format
GET /QuestionnaireResponse?_format=json&_count=100
```

## Data Anonymization

### PII Removal

All export mechanisms automatically remove or anonymize personally identifiable information:

- **Patient Names**: Replaced with anonymized identifiers
- **Phone Numbers**: Removed or hashed
- **Addresses**: Generalized to district/state level
- **Date of Birth**: Converted to age ranges
- **Images**: Excluded from bulk exports (privacy protection)

### Anonymization Process

```json
{
  "resourceType": "Patient",
  "id": "patient-123",
  "identifier": [
    {
      "system": "https://artpark.in/patient-id",
      "value": "AA-2023-001"
    }
  ],
  "gender": "male",
  "birthDate": "1980-01-01",
  "address": [
    {
      "district": "Bangalore Urban",
      "state": "Karnataka",
      "country": "IN"
    }
  ]
}
```

## Export Data Types

### 1. Screening Data

**Questionnaire Responses:**
- Screening questionnaire responses
- Risk assessment data
- Symptom documentation
- Follow-up scheduling

**Sample Export:**
```json
{
  "resourceType": "QuestionnaireResponse",
  "questionnaire": "Questionnaire/oral-cancer-screening",
  "status": "completed",
  "subject": {
    "reference": "Patient/patient-123"
  },
  "item": [
    {
      "linkId": "symptoms",
      "answer": [
        {
          "valueString": "white_patches"
        }
      ]
    }
  ]
}
```

### 2. Clinical Observations

**Screening Results:**
- Visual examination findings
- Risk assessments
- Referral recommendations
- Follow-up requirements

**Sample Export:**
```json
{
  "resourceType": "Observation",
  "code": {
    "coding": [
      {
        "system": "http://snomed.info/sct",
        "code": "oral-cancer-screening",
        "display": "Oral Cancer Screening"
      }
    ]
  },
  "subject": {
    "reference": "Patient/patient-123"
  },
  "valueCodeableConcept": {
    "coding": [
      {
        "system": "https://artpark.in/screening-result",
        "code": "high-risk",
        "display": "High Risk"
      }
    ]
  }
}
```

### 3. Aggregate Data

**Statistical Information:**
- Screening coverage by region
- Detection rates
- Referral patterns
- Follow-up completion rates

**Sample Export:**
```json
{
  "resourceType": "MeasureReport",
  "measure": "Measure/oral-cancer-screening-coverage",
  "status": "complete",
  "period": {
    "start": "2023-01-01",
    "end": "2023-12-31"
  },
  "group": [
    {
      "code": {
        "coding": [
          {
            "system": "https://artpark.in/region",
            "code": "bangalore-urban",
            "display": "Bangalore Urban"
          }
        ]
      },
      "population": [
        {
          "code": {
            "coding": [
              {
                "system": "http://terminology.hl7.org/CodeSystem/measure-population",
                "code": "initial-population"
              }
            ]
          },
          "count": 1000
        }
      ]
    }
  ]
}
```

## Export Configuration

### Access Control

**Role-Based Access:**
- **Data Analyst**: Read-only access to anonymized data
- **Healthcare Provider**: Access to patient-specific data (with authorization)
- **System Administrator**: Full access for system management

**Authentication:**
```bash
# OAuth 2.0 with specific scopes
curl -H "Authorization: Bearer your-access-token" \
     -H "Content-Type: application/fhir+json" \
     "https://your-fhir-server.com/fhir/QuestionnaireResponse"
```

### Rate Limiting

To prevent system overload, export operations are rate-limited:

- **API Calls**: 100 requests per minute per user
- **Bulk Exports**: 1 export job per hour per user
- **File Downloads**: 10MB per minute

### Data Retention

**Export Logs:**
- All export operations are logged
- Logs retained for 1 year
- Audit trail available for compliance

## Integration Examples

### 1. Dashboard Integration

```javascript
// Fetch screening statistics for dashboard
fetch('/fhir/MeasureReport?measure=oral-cancer-screening-coverage', {
  headers: {
    'Authorization': 'Bearer ' + accessToken,
    'Content-Type': 'application/fhir+json'
  }
})
.then(response => response.json())
.then(data => {
  // Process data for dashboard display
  updateDashboard(data);
});
```

### 2. Analytics Integration

```python
import requests
import pandas as pd

# Export questionnaire responses for analysis
response = requests.get(
    'https://your-fhir-server.com/fhir/QuestionnaireResponse',
    headers={'Authorization': f'Bearer {access_token}'},
    params={'_count': 1000, '_format': 'json'}
)

data = response.json()
df = pd.json_normalize(data['entry'], record_path=['resource'])

# Perform analytics
screening_results = df.groupby('status').size()
```

### 3. Reporting Integration

```bash
# Export data for monthly report
curl -X GET \
  "https://your-fhir-server.com/fhir/$export" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Parameters",
    "parameter": [
      {
        "name": "_since",
        "valueDateTime": "2023-12-01T00:00:00Z"
      },
      {
        "name": "_type",
        "valueString": "QuestionnaireResponse,Observation"
      }
    ]
  }'
```

## Compliance and Security

### Data Protection

- **Encryption**: All data encrypted in transit and at rest
- **Access Logging**: All export operations logged for audit
- **Data Minimization**: Only necessary data exported
- **Consent Management**: Export respects patient consent

### Regulatory Compliance

- **HIPAA**: Compliant with healthcare privacy regulations
- **GDPR**: Respects data protection requirements
- **Indian Healthcare Regulations**: Compliant with local healthcare data laws

## Troubleshooting

### Common Issues

**Authentication Errors:**
- Verify access token is valid
- Check user permissions
- Ensure proper OAuth configuration

**Export Failures:**
- Check FHIR server connectivity
- Verify resource availability
- Monitor system resources

**Data Quality Issues:**
- Validate data format
- Check for missing required fields
- Verify anonymization process

### Support

For export-related issues:
- **Documentation**: Check this guide and API documentation
- **Technical Support**: Contact ARTPARK at connect@artpark.in
- **Community**: Post issues on GitHub repository

## Conclusion

Aarogya Aarohan provides comprehensive data export capabilities that enable users to access and analyze healthcare data while maintaining privacy and security. The export mechanisms support various use cases including analytics, reporting, and integration with other healthcare systems.

All export functionality is designed to comply with healthcare data regulations and privacy standards, ensuring that the application can be safely used as a digital public good while protecting patient privacy. 