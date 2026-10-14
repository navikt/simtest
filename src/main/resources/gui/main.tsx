import "@navikt/ds-css";
import { BodyLong, BodyShort, Box, Button, CopyButton, Heading, Switch, Textarea, TextField, Theme, VStack } from "@navikt/ds-react";
import { createRoot } from "react-dom/client";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import "./styles.css";

type Login = { state: "checking" | "signed-out" | "error" } | { state: "signed-in"; name: string };
const initialAppName = "dev-gcp:platforce:saas-proxy";

export function App() {
    const [theme, setTheme] = useState<"dark" | "light">("dark");
    const [login, setLogin] = useState<Login>({ state: "checking" });
    const [appName, setAppName] = useState(initialAppName);
    const [token, setToken] = useState("");
    const [status, setStatus] = useState("");
    const [fieldError, setFieldError] = useState("");
    const [loginExpired, setLoginExpired] = useState(false);
    const [loading, setLoading] = useState(false);
    const request = useRef<AbortController | null>(null);
    const loadingRef = useRef(false);
    const tokenTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const loginLink = useRef<HTMLAnchorElement | null>(null);
    const generation = useRef(0);

    const clearToken = useCallback(() => {
        generation.current++;
        if (tokenTimer.current) clearTimeout(tokenTimer.current);
        tokenTimer.current = null;
        request.current?.abort();
        request.current = null;
        loadingRef.current = false;
        setToken("");
        setLoading(false);
    }, []);

    useEffect(() => {
        if (loginExpired && login.state === "signed-out") loginLink.current?.focus();
    }, [loginExpired, login]);

    useEffect(() => {
        document.documentElement.classList.toggle("dark", theme === "dark");
        document.documentElement.classList.toggle("light", theme === "light");
    }, [theme]);

    useEffect(() => {
        const controller = new AbortController();
        async function checkLogin() {
            try {
                const response = await fetch("/internal/secrethello", { cache: "no-store", signal: controller.signal });
                if (response.status === 401) {
                    setLogin({ state: "signed-out" });
                } else if (response.ok) {
                    const name = (await response.text()).trim();
                    if (!controller.signal.aborted) setLogin({ state: "signed-in", name: name || "Du" });
                } else {
                    setLogin({ state: "error" });
                }
            } catch {
                if (!controller.signal.aborted) setLogin({ state: "error" });
            }
        }
        void checkLogin();
        return () => controller.abort();
    }, []);

    useEffect(() => {
        const clearOnHide = () => {
            clearToken();
            setStatus("");
        };
        const onVisibilityChange = () => {
            if (document.hidden) clearOnHide();
        };
        const onPageShow = (event: PageTransitionEvent) => {
            if (event.persisted) clearOnHide();
        };
        document.addEventListener("visibilitychange", onVisibilityChange);
        window.addEventListener("pagehide", clearOnHide);
        window.addEventListener("pageshow", onPageShow);
        return () => {
            document.removeEventListener("visibilitychange", onVisibilityChange);
            window.removeEventListener("pagehide", clearOnHide);
            window.removeEventListener("pageshow", onPageShow);
            if (tokenTimer.current) clearTimeout(tokenTimer.current);
            request.current?.abort();
        };
    }, [clearToken]);

    async function exchange(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (login.state !== "signed-in" || loadingRef.current) return;
        clearToken();
        setFieldError("");
        const currentGeneration = generation.current;
        const controller = new AbortController();
        request.current = controller;
        loadingRef.current = true;
        setLoading(true);
        setStatus("Henter token …");
        try {
            const response = await fetch("/internal/tokenexchange-name", {
                method: "POST",
                headers: { "Content-Type": "text/plain" },
                body: appName,
                cache: "no-store",
                signal: controller.signal,
            });
            if (controller.signal.aborted || generation.current !== currentGeneration) return;
            if (response.status === 401) {
                setLoginExpired(true);
                setLogin({ state: "signed-out" });
                setStatus("");
            } else if (response.status === 400) {
                setFieldError("Ugyldig appnavn. Bruk formatet cluster:namespace:appnavn, med dev-gcp eller dev-fss som cluster.");
                setStatus("Kontroller appnavnet og prøv igjen.");
            } else if (response.status === 403) {
                setStatus("Forespørselen ble avvist. Kontroller innloggingen og prøv igjen.");
            } else if (!response.ok) {
                setStatus("Kunne ikke hente token. Prøv igjen senere.");
            } else {
                const value = await response.text();
                if (controller.signal.aborted || generation.current !== currentGeneration) return;
                if (value) {
                    setToken(value);
                    tokenTimer.current = setTimeout(clearToken, 5 * 60 * 1000);
                    setStatus("Tokenet er klart.");
                } else {
                    setStatus("Tjenesten returnerte ikke et token. Prøv igjen senere.");
                }
            }
        } catch {
            if (!controller.signal.aborted && generation.current === currentGeneration) {
                setStatus("Kunne ikke hente token. Prøv igjen senere.");
            }
        } finally {
            if (generation.current === currentGeneration) {
                request.current = null;
                loadingRef.current = false;
                setLoading(false);
            }
        }
    }

    return (
        <Theme theme={theme}>
            <Box as="main" className="page" padding={{ xs: "space-16", md: "space-32" }}>
                <VStack gap="space-32">
                    <header className="page-header">
                        <VStack gap="space-12">
                            <Switch checked={theme === "light"} onChange={(event) => setTheme(event.target.checked ? "light" : "dark")}>Lyst tema</Switch>
                            <Heading level="1" size="large">Tokenveksling</Heading>
                            <BodyLong>Hent et token for en app i dev, på vegne av deg selv via simtest.</BodyLong>
                        </VStack>
                    </header>

                    <Box as="section" aria-labelledby="login-heading" background="raised" borderColor="neutral-subtle" borderWidth="1" borderRadius="12" padding="space-24">
                        <VStack gap="space-16">
                            <Heading id="login-heading" level="2" size="small">Innlogging</Heading>
                            <div role="status" aria-live="polite">
                                {login.state === "checking" && <BodyShort>Sjekker innlogging …</BodyShort>}
                                {login.state === "signed-in" && <BodyShort>{login.name} er logget inn i simtest.</BodyShort>}
                                {login.state === "error" && <BodyShort>Kunne ikke kontrollere innloggingen. Last siden på nytt.</BodyShort>}
                                {login.state === "signed-out" && (
                                    <VStack gap="space-12" align="start">
                                        <BodyShort>{loginExpired ? "Innloggingen er utløpt. Logg inn på nytt." : "Logg inn med Nav-brukeren din for å hente et token."}</BodyShort>
                                        <Button as="a" ref={loginLink} href="/oauth2/login?redirect=/internal/gui">Logg inn med Nav</Button>
                                    </VStack>
                                )}
                            </div>
                        </VStack>
                    </Box>

                    {login.state === "signed-in" && (
                        <>
                            <Box as="section" aria-labelledby="exchange-heading" background="raised" borderColor="neutral-subtle" borderWidth="1" borderRadius="12" padding="space-24">
                                <VStack gap="space-16">
                                    <Heading id="exchange-heading" level="2" size="small">Velg app</Heading>
                                    <form onSubmit={(event) => void exchange(event)}>
                                        <VStack gap="space-16" align="start">
                                            <TextField
                                                className="app-name"
                                                label="Entra-appnavn"
                                                description="Skriv cluster:namespace:appnavn, for eksempel dev-gcp:platforce:saas-proxy. Scopet blir api://dev-gcp.platforce.saas-proxy/.default."
                                                value={appName}
                                                error={fieldError || undefined}
                                                autoComplete="off"
                                                spellCheck={false}
                                                onChange={(event) => {
                                                    clearToken();
                                                    setStatus("");
                                                    setFieldError("");
                                                    setAppName(event.target.value);
                                                }}
                                            />
                                            <Button type="submit" loading={loading}>Hent token</Button>
                                        </VStack>
                                    </form>
                                </VStack>
                            </Box>

                            <Box as="section" aria-labelledby="result-heading" background="raised" borderColor="neutral-subtle" borderWidth="1" borderRadius="12" padding="space-24">
                                <VStack gap="space-16" align="start">
                                    <Heading id="result-heading" level="2" size="small">Token</Heading>
                                    <BodyShort>Tokenet gir tilgang på vegne av deg via appen simtest. Ikke del det. Slett det fra Postman etter bruk.</BodyShort>
                                    {token && (
                                        <>
                                            <Textarea className="token-value" label="Token for valgt app" value={token} readOnly minRows={4} />
                                            <CopyButton copyText={token} text="Kopier token" activeText="Tokenet er kopiert" />
                                        </>
                                    )}
                                    <div role="status" aria-live="polite"><BodyShort>{status}</BodyShort></div>
                                </VStack>
                            </Box>
                        </>
                    )}
                </VStack>
            </Box>
        </Theme>
    );
}

const root = document.getElementById("root");
if (root) createRoot(root).render(<App />);
