import http from "k6/http";
import { BASE_URL, endpoints, jsonHeaders, checkResponse } from "./helpers.js";

export const options = {
  scenarios: {
    constant_json_get: {
      executor: "constant-arrival-rate",
      rate: 500,
      timeUnit: "1s",
      duration: "60s",
      preAllocatedVUs: 50,
      maxVUs: 200,
      exec: "jsonGet",
    },
    constant_echo_post: {
      executor: "constant-arrival-rate",
      rate: 300,
      timeUnit: "1s",
      duration: "60s",
      preAllocatedVUs: 30,
      maxVUs: 150,
      exec: "echoPost",
    },
    constant_delay: {
      executor: "constant-arrival-rate",
      rate: 100,
      timeUnit: "1s",
      duration: "60s",
      preAllocatedVUs: 50,
      maxVUs: 500,
      exec: "delayGet",
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<500", "p(99)<1000"],
    http_req_failed: ["rate<0.01"],
  },
};

export function jsonGet() {
  const res = http.get(`${BASE_URL}${endpoints.json}`);
  checkResponse(res, 200);
}

export function echoPost() {
  const payload = JSON.stringify({ data: "benchmark", count: 42 });
  const res = http.post(`${BASE_URL}${endpoints.echo}`, payload, {
    headers: jsonHeaders,
  });
  checkResponse(res, 200);
}

export function delayGet() {
  const res = http.get(`${BASE_URL}${endpoints.delay}?ms=100`);
  checkResponse(res, 200);
}
