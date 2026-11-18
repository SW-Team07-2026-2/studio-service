#!/bin/sh
# LocalStack 이 뜨면 한 번 — 로컬 이미지 버킷 + 관리자 화면(localhost:5173)에서 PUT 할 수 있게 CORS
awslocal s3 mb s3://indig-images-local
awslocal s3api put-bucket-cors --bucket indig-images-local --cors-configuration '{"CORSRules":[{"AllowedOrigins":["http://localhost:5173"],"AllowedMethods":["PUT","GET"],"AllowedHeaders":["*"],"MaxAgeSeconds":3000}]}'
