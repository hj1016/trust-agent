import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, csrfToken, request } from "./api";

describe("api client", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("reads the XSRF cookie", () => {
    expect(csrfToken("JSESSIONID=x; XSRF-TOKEN=abc%3D%3D; other=1")).toBe("abc==");
    expect(csrfToken("JSESSIONID=x")).toBeUndefined();
  });

  it("sends the CSRF header only on state-changing requests and same-origin credentials", async () => {
    document.cookie = "XSRF-TOKEN=token-1";
    const fetchMock = vi.fn().mockImplementation(async () => new Response("{}", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await request("/api/v1/session");
    await request("/api/v1/consultations", { json: { applicationId: "A" } });
    const [, getInit] = fetchMock.mock.calls[0];
    const [, postInit] = fetchMock.mock.calls[1];
    expect(getInit.method).toBe("GET");
    expect(getInit.headers["X-XSRF-TOKEN"]).toBeUndefined();
    expect(postInit.method).toBe("POST");
    expect(postInit.headers["X-XSRF-TOKEN"]).toBe("token-1");
    expect(postInit.credentials).toBe("same-origin");
  });

  it("turns problem details into ApiError with code", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "SECTION_ON_HOLD", detail: "보류" }), { status: 409 })));
    await expect(request("/x", { json: {} })).rejects.toMatchObject({ status: 409, code: "SECTION_ON_HOLD", detail: "보류" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>", { status: 502 })));
    const error = (await request("/x").catch((caught: unknown) => caught)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe("HTTP_502");
  });
});
