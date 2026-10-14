// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { App } from "./main";

const accessToken = "sample.token.value";

function response(status: number, body = "") {
    return { status, ok: status >= 200 && status < 300, text: async () => body } as Response;
}

function mount() {
    return render(<App />);
}

describe("Aksel token GUI", () => {
    beforeEach(() => {
        document.documentElement.className = "dark";
        vi.stubGlobal("fetch", vi.fn(async () => response(200, "Kari Nordmann")));
        Object.defineProperty(document, "hidden", { configurable: true, value: false });
    });

    afterEach(() => {
        cleanup();
        vi.unstubAllGlobals();
    });

    it("starter i mørkt tema og lar brukeren bytte til lyst", async () => {
        mount();
        await screen.findByText("Kari Nordmann er logget inn i simtest.");
        expect(document.documentElement.classList.contains("dark")).toBe(true);
        fireEvent.click(screen.getByRole("checkbox", { name: "Lyst tema" }));
        expect(document.documentElement.classList.contains("light")).toBe(true);
    });

    it("tilbyr innlogging uten token og veksler ikke før bruker har logget inn", async () => {
        vi.stubGlobal("fetch", vi.fn(async () => response(401)));
        mount();
        expect((await screen.findByRole("button", { name: "Logg inn med Nav" }) as HTMLAnchorElement).getAttribute("href"))
            .toBe("/oauth2/login?redirect=/internal/gui");
        expect(screen.queryByRole("button", { name: "Hent token" })).toBeNull();
    });

    it("sender bare appnavnet og viser vekslet token etter vellykket svar", async () => {
        const fetchMock = vi.fn(async (url: string) => url === "/internal/secrethello" ? response(200, "Du") : response(200, accessToken));
        vi.stubGlobal("fetch", fetchMock);
        mount();
        await screen.findByText("Du er logget inn i simtest.");
        fireEvent.click(screen.getByRole("button", { name: "Hent token" }));
        const field = await screen.findByRole("textbox", { name: "Token for valgt app" });
        expect((field as HTMLTextAreaElement).value).toBe(accessToken);
        expect(fetchMock).toHaveBeenCalledWith("/internal/tokenexchange-name", expect.objectContaining({
            method: "POST", body: "dev-gcp:platforce:saas-proxy", cache: "no-store",
        }));
        expect(screen.getByRole("button", { name: "Kopier token" })).toBeTruthy();
        fireEvent.change(screen.getByRole("textbox", { name: "Entra-appnavn" }), { target: { value: "dev-gcp:platforce:annen-app" } });
        expect(screen.queryByRole("textbox", { name: "Token for valgt app" })).toBeNull();
    });

    it("tømmer token når fanen skjules og ved tilbakebuffer", async () => {
        const fetchMock = vi.fn(async (url: string) => url === "/internal/secrethello" ? response(200, "Kari") : response(200, accessToken));
        vi.stubGlobal("fetch", fetchMock);
        mount();
        await screen.findByText("Kari er logget inn i simtest.");
        fireEvent.click(screen.getByRole("button", { name: "Hent token" }));
        await screen.findByRole("textbox", { name: "Token for valgt app" });
        Object.defineProperty(document, "hidden", { configurable: true, value: true });
        fireEvent(document, new Event("visibilitychange"));
        await waitFor(() => expect(screen.queryByRole("textbox", { name: "Token for valgt app" })).toBeNull());
        Object.defineProperty(document, "hidden", { configurable: true, value: false });
        fireEvent.click(screen.getByRole("button", { name: "Hent token" }));
        await screen.findByRole("textbox", { name: "Token for valgt app" });
        const restoredPage = new Event("pageshow") as PageTransitionEvent;
        Object.defineProperty(restoredPage, "persisted", { value: true });
        fireEvent(window, restoredPage);
        await waitFor(() => expect(screen.queryByRole("textbox", { name: "Token for valgt app" })).toBeNull());
    });

    it("beholder ikke token ved feil og krever ny innlogging ved 401", async () => {
        const fetchMock = vi.fn(async (url: string) => url === "/internal/secrethello" ? response(200, "Kari") : response(401));
        vi.stubGlobal("fetch", fetchMock);
        mount();
        await screen.findByText("Kari er logget inn i simtest.");
        fireEvent.click(screen.getByRole("button", { name: "Hent token" }));
        await screen.findByRole("button", { name: "Logg inn med Nav" });
        expect(screen.getByText("Innloggingen er utløpt. Logg inn på nytt.")).toBeTruthy();
        await waitFor(() => expect(document.activeElement?.textContent).toContain("Logg inn med Nav"));
        expect(screen.queryByRole("textbox", { name: "Token for valgt app" })).toBeNull();
    });

    it("viser ugyldig appnavn på tekstfeltet", async () => {
        const fetchMock = vi.fn(async (url: string) => url === "/internal/secrethello" ? response(200, "Kari") : response(400));
        vi.stubGlobal("fetch", fetchMock);
        mount();
        await screen.findByText("Kari er logget inn i simtest.");
        fireEvent.click(screen.getByRole("button", { name: "Hent token" }));
        expect(await screen.findByText(/Ugyldig appnavn/)).toBeTruthy();
        expect(screen.getByRole("textbox", { name: "Entra-appnavn" }).getAttribute("aria-invalid")).toBe("true");
        expect(screen.queryByRole("textbox", { name: "Token for valgt app" })).toBeNull();
    });
});
