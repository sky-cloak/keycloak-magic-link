<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "header">
        ${msg("magicLinkSentTitle")}
    <#elseif section = "form">
        <p class="instruction">${msg("magicLinkSentInstruction")}</p>
    </#if>
</@layout.registrationLayout>
