const amplifyConfig = r'''{
  "auth": {
    "user_pool_id": "ap-southeast-1_iLtBqhA7G",
    "aws_region": "ap-southeast-1",
    "user_pool_client_id": "j9hj5b1lq0e97b9vmr6c1poaq",
    "identity_pool_id": "ap-southeast-1:d753a51f-696b-4cbb-b4bf-3184f246918d",
    "mfa_methods": [],
    "standard_required_attributes": [
      "email"
    ],
    "username_attributes": [
      "email"
    ],
    "user_verification_types": [
      "email"
    ],
    "groups": [],
    "mfa_configuration": "NONE",
    "password_policy": {
      "min_length": 8,
      "require_lowercase": true,
      "require_numbers": true,
      "require_symbols": true,
      "require_uppercase": true
    },
    "unauthenticated_identities_enabled": true
  },
  "storage": {
    "aws_region": "ap-southeast-1",
    "bucket_name": "amplify-otomobileapp-klau-amplifystoragebucket7f87-cdr1h8qj5yty",
    "buckets": [
      {
        "name": "amplifyStorage",
        "bucket_name": "amplify-otomobileapp-klau-amplifystoragebucket7f87-cdr1h8qj5yty",
        "aws_region": "ap-southeast-1",
        "paths": {
          "rawImage/*": {
            "guest": [
              "get",
              "list"
            ]
          },
          "media/*": {
            "guest": [
              "get",
              "list"
            ]
          }
        }
      }
    ]
  },
  "version": "1.4"
}''';