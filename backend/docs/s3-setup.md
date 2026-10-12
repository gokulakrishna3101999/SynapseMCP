# S3 staging bucket setup (us-east-1)

The bucket is transient staging only: every object is deleted by the worker right after indexing.
The 24h lifecycle rule below is the safety net for orphans (failed commits, crashed workers).

Replace `YOUR_BUCKET` with your globally unique bucket name and export `S3_BUCKET=YOUR_BUCKET`.

## 1. Create the bucket with public access blocked
```bash
aws s3api create-bucket --bucket YOUR_BUCKET --region us-east-1
aws s3api put-public-access-block --bucket YOUR_BUCKET --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```
(`us-east-1` must NOT be passed as a `LocationConstraint`.)

## 2. 24-hour expiry for anything under `tenants/`
`put-bucket-lifecycle-configuration` REPLACES the bucket's whole lifecycle config; this bucket should have no other rules.
```bash
aws s3api put-bucket-lifecycle-configuration --bucket YOUR_BUCKET --lifecycle-configuration '{
  "Rules": [{
    "ID": "expire-orphaned-staging-24h",
    "Status": "Enabled",
    "Filter": { "Prefix": "tenants/" },
    "Expiration": { "Days": 1 },
    "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
  }]
}'
```

## 3. Minimal IAM policy for the application identity
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::YOUR_BUCKET/tenants/*"
  }]
}
```
The app reads its credentials explicitly from `AWS_ACCESS_KEY` and `AWS_SECRET_KEY` (not the AWS default provider chain), so machine-level `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` are ignored.
