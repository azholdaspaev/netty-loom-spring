#!/usr/bin/env bash
# Every comment on a pull request, projected to what deduplication and a reply need.
# Read-only. Usage: .claude/scripts/pr-comments.sh <PR number or URL>
#
# Three REST endpoints hold the three comment kinds, and `--paginate` emits one array per
# page, hence the `jq -s add` fold. Thread ids and resolution state exist only in GraphQL,
# where `--paginate` needs the `$endCursor` variable and the `pageInfo` selection to work.
set -euo pipefail

url=$(gh pr view "${1:?usage: pr-comments.sh <PR number or URL>}" --json url --jq .url)
pr=${url##*/}
repo=${url#https://github.com/}
repo=${repo%/pull/*}
owner=${repo%/*}
name=${repo#*/}

inline=$(gh api --paginate "repos/$repo/pulls/$pr/comments?per_page=100" \
  --jq '[.[] | {id, kind: "inline", path, line, original_line, author: .user.login, in_reply_to_id, body: .body[0:400]}]' \
  | jq -s 'add // []')

conversation=$(gh api --paginate "repos/$repo/issues/$pr/comments?per_page=100" \
  --jq '[.[] | {id, kind: "conversation", author: .user.login, body: .body[0:400]}]' \
  | jq -s 'add // []')

reviews=$(gh api --paginate "repos/$repo/pulls/$pr/reviews?per_page=100" \
  --jq '[.[] | select(.body != "") | {id, kind: "review", author: .user.login, state, body: .body[0:400]}]' \
  | jq -s 'add // []')

threads=$(gh api graphql --paginate -F owner="$owner" -F name="$name" -F pr="$pr" -f query='
  query($owner: String!, $name: String!, $pr: Int!, $endCursor: String) {
    repository(owner: $owner, name: $name) {
      pullRequest(number: $pr) {
        reviewThreads(first: 100, after: $endCursor) {
          pageInfo { hasNextPage endCursor }
          nodes { id isResolved isOutdated comments(first: 1) { nodes { databaseId } } }
        }
      }
    }
  }' --jq '[.data.repository.pullRequest.reviewThreads.nodes[]
           | {id, isResolved, isOutdated, firstCommentId: .comments.nodes[0].databaseId}]' \
  | jq -s 'add // []')

jq -n --argjson inline "$inline" --argjson conversation "$conversation" \
      --argjson reviews "$reviews" --argjson threads "$threads" \
      '{inline: $inline, conversation: $conversation, reviews: $reviews, threads: $threads}'
