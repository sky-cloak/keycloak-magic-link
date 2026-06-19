<#-- skycloak-magic-link themed email (HTML). Realms can override this in their own email theme. -->
<html>
<body style="font-family:sans-serif">
<p>${msg("magicLinkEmailGreeting")}</p>
<p>${msg("magicLinkEmailBody", clientName)}</p>
<p><a href="${link}">${msg("magicLinkEmailButton")}</a></p>
<p>${msg("magicLinkEmailExpiry")}</p>
</body>
</html>
