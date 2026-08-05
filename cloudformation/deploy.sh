#!/usr/bin/env bash
#
# Stage a CloudFormation template in S3, then validate/deploy it from there
# (CLAUDE.md 3.8).
#
# WHY S3 STAGING RATHER THAN --template-body: the AWS API caps an INLINE
# template (--template-body) at 51,200 bytes. chat-app-stack.yaml crossed
# that limit once the step-12 hardening comments landed, so
# `validate-template --template-body` and `update-stack --template-body`
# both started failing with a generic "1 validation error detected" that
# dumps the whole template back at you and looks nothing like a size error.
# A template referenced by --template-url (S3) gets a much larger ceiling
# (460,800 bytes), so staging is the fix rather than deleting the comments
# this project deliberately keeps verbose. Staging happens INSIDE this
# script, on every invocation, specifically so the uploaded copy can never
# silently drift from the local file the way a separate "remember to upload
# first" step would.
#
# USAGE
#   ./cloudformation/deploy.sh validate [template-file]
#   ./cloudformation/deploy.sh stage    [template-file]
#   ./cloudformation/deploy.sh deploy   <stack-name> <template-file> [extra aws args...]
#
# EXAMPLES
#   # Just check the template is well-formed (no AWS changes at all):
#   ./cloudformation/deploy.sh validate cloudformation/chat-app-stack.yaml
#
#   # Frontend stack - no parameters:
#   ./cloudformation/deploy.sh deploy chat-app-frontend cloudformation/frontend-stack.yaml
#
#   # Backend stack - keep every existing parameter, change nothing:
#   ./cloudformation/deploy.sh deploy chat-app cloudformation/chat-app-stack.yaml \
#     --parameters file:///tmp/chat-app-update-params.json
#
# The backend stack's secret parameters (MysqlRootPassword, JwtSecret) are
# NOT handled here on purpose - they live in .env.aws.local (gitignored) and
# are passed explicitly via --parameters when they actually need to change.
# Every other run should use UsePreviousValue so a deploy can't silently
# reset a parameter to a template default.
set -euo pipefail

BUCKET="${CFN_TEMPLATE_BUCKET:-chat-app-cfn-templates-786566430552}"
REGION="${AWS_REGION:-us-east-1}"

# CAPABILITY_NAMED_IAM is required by chat-app-stack.yaml because it creates
# an explicitly-NAMED IAM role (chat-app-ec2-ecr-pull-role). AWS makes you
# acknowledge that deliberately - a named role can collide with an existing
# one, unlike an auto-generated name. Harmless to pass for the frontend
# stack, which creates no IAM resources at all.
CAPABILITIES="CAPABILITY_NAMED_IAM"

usage() {
  sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

# Uploads the template and echoes the https:// URL the CloudFormation API
# wants. Not the s3:// form - validate-template/create-stack/update-stack
# all reject that.
stage() {
  local template="$1"
  local key
  key="$(basename "$template")"
  aws s3 cp "$template" "s3://${BUCKET}/${key}" --only-show-errors >&2
  echo "https://${BUCKET}.s3.${REGION}.amazonaws.com/${key}"
}

cmd_validate() {
  local template="${1:-cloudformation/chat-app-stack.yaml}"
  local url
  url="$(stage "$template")"
  echo "staged: $url" >&2
  aws cloudformation validate-template --region "$REGION" --template-url "$url"
}

cmd_stage() {
  local template="${1:-cloudformation/chat-app-stack.yaml}"
  stage "$template"
}

cmd_deploy() {
  local stack="${1:-}"
  local template="${2:-}"
  [ -n "$stack" ] && [ -n "$template" ] || usage
  shift 2

  local url
  url="$(stage "$template")"
  echo "staged: $url" >&2

  # Validate before touching the stack - a malformed template caught here
  # costs nothing, whereas one caught mid-update can leave the stack in
  # UPDATE_ROLLBACK_COMPLETE and block the next attempt.
  aws cloudformation validate-template --region "$REGION" --template-url "$url" >/dev/null
  echo "template validated" >&2

  # describe-stacks is the cheapest existence check; a nonzero exit here
  # means the stack simply doesn't exist yet, not that something failed.
  if aws cloudformation describe-stacks --region "$REGION" --stack-name "$stack" >/dev/null 2>&1; then
    echo "stack '$stack' exists -> update-stack" >&2
    # "No updates are to be performed" is AWS's way of saying the template
    # and parameters already match reality. That's a successful no-op, not
    # a failure, so it's caught here rather than exiting nonzero and
    # failing a CI run for no reason.
    if ! aws cloudformation update-stack \
          --region "$REGION" \
          --stack-name "$stack" \
          --template-url "$url" \
          --capabilities "$CAPABILITIES" \
          "$@" 2>/tmp/cfn-update-err.txt; then
      if grep -q "No updates are to be performed" /tmp/cfn-update-err.txt; then
        echo "no changes to apply - stack already matches this template" >&2
        rm -f /tmp/cfn-update-err.txt
        return 0
      fi
      cat /tmp/cfn-update-err.txt >&2
      rm -f /tmp/cfn-update-err.txt
      return 1
    fi
    aws cloudformation wait stack-update-complete --region "$REGION" --stack-name "$stack"
  else
    echo "stack '$stack' does not exist -> create-stack" >&2
    aws cloudformation create-stack \
      --region "$REGION" \
      --stack-name "$stack" \
      --template-url "$url" \
      --capabilities "$CAPABILITIES" \
      "$@"
    aws cloudformation wait stack-create-complete --region "$REGION" --stack-name "$stack"
  fi

  echo "" >&2
  echo "=== outputs ===" >&2
  aws cloudformation describe-stacks --region "$REGION" --stack-name "$stack" \
    --query 'Stacks[0].Outputs[].{Key:OutputKey,Value:OutputValue}' --output table
}

case "${1:-}" in
  validate) shift; cmd_validate "$@" ;;
  stage)    shift; cmd_stage "$@" ;;
  deploy)   shift; cmd_deploy "$@" ;;
  *)        usage ;;
esac
