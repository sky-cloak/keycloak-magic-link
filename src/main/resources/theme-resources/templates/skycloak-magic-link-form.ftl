<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${msg("magicLinkFormTitle")}
    <#elseif section = "form">
        <form id="kc-magic-link-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="email" class="${properties.kcLabelClass!}">${msg("magicLinkEmailLabel")}</label>
                <input type="email" id="email" name="email" autofocus autocomplete="email"
                       class="${properties.kcInputClass!}"/>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <input type="submit" value="${msg("magicLinkFormSubmit")}"
                       class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"/>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
