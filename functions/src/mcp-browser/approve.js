import { initializeApp } from "https://www.gstatic.com/firebasejs/12.17.1/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, signInWithPopup, inMemoryPersistence, setPersistence,
} from "https://www.gstatic.com/firebasejs/12.17.1/firebase-auth.js";

const element = (id) => document.getElementById(id);
const requestId = new URL(location.href).searchParams.get("request");
let authentication;
let householdId;
const status = (message) => { element("status").textContent = message; };

async function post(action, body = {}) {
  const headers = { "Content-Type": "application/json" };
  if (authentication?.currentUser) {
    headers.Authorization = `Bearer ${await authentication.currentUser.getIdToken()}`;
  }
  const response = await fetch(`/oauth/${action}`, {
    method: "POST", headers, body: JSON.stringify({ requestId, ...body }),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error_description ?? "Unable to connect. Try again from your application.");
  return result;
}

element("sign-in").onclick = async () => {
  try {
    element("consent").hidden = true;
    element("sign-in").disabled = true;
    if (!authentication) {
      const config = await (await fetch("/oauth/config")).json();
      authentication = getAuth(initializeApp(config));
      await setPersistence(authentication, inMemoryPersistence);
    }
    const provider = new GoogleAuthProvider();
    provider.setCustomParameters({ prompt: "select_account" });
    await signInWithPopup(authentication, provider);
    const preview = await post("preview");
    householdId = preview.householdId;
    element("household").textContent = preview.householdName;
    element("client").textContent = preview.clientName;
    element("consent").hidden = false;
    status("");
  } catch (error) { status(error.message); }
  finally { element("sign-in").disabled = false; }
};

element("approve").onclick = async () => {
  element("approve").disabled = true;
  try {
    const result = await post("approve", { householdId });
    location.assign(result.redirect);
  } catch (error) { status(error.message); element("approve").disabled = false; }
};
element("cancel").onclick = async () => {
  try { location.assign((await post("cancel")).redirect); }
  catch (error) { status(error.message); }
};
