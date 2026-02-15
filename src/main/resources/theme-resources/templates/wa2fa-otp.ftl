<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('otp') displayInfo=true; section>
    <#if section = "header">
        ${msg("wa2fa.otp.title","WhatsApp Verification")}
    <#elseif section = "form">
        <form id="kc-otp-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">

            <#-- Resolve feature flags -->
            <#assign isQrEnabled = (qrEnabled!false) && (qrWaMeLink??)>
            <#assign isOtpEnabled = (otpEnabled!true)>

            <#if isQrEnabled && !isOtpEnabled>
                <#-- ============================================ -->
                <#-- QR-ONLY MODE: No OTP tab, no tabs bar needed -->
                <#-- ============================================ -->

                <#-- Phone number hint -->
                <#if (phoneLast4!'') != ''>
                    <p style="text-align:center; margin-bottom:1em; color:#555;">
                        ${msg("wa2fa.otp.sentToPhone","Verify your WhatsApp number ending in")} <strong>${phoneLast4}</strong>
                    </p>
                </#if>

                <div style="text-align:center; padding:1em 0;">
                    <p style="margin-bottom:1em; color:#555;">
                        ${msg("wa2fa.qr.instruction","Scan this QR code with your phone camera. It will open WhatsApp with a pre-filled verification message. Just tap Send.")}
                    </p>

                    <div id="qr-code-container" style="display:inline-block; padding:16px; background:#fff; border:1px solid #e0e0e0; border-radius:8px;">
                        <canvas id="qr-canvas"></canvas>
                    </div>

                    <p style="margin-top:1em; font-size:0.9em; color:#888;">
                        ${msg("wa2fa.qr.tokenLabel","Verification Code")}: <strong>${(qrToken!'')}</strong>
                    </p>

                    <#-- Status indicator -->
                    <div id="qr-status" style="margin-top:1em; padding:10px; border-radius:4px; display:none;">
                    </div>

                    <#-- Mobile fallback: direct link -->
                    <p style="margin-top:1em;">
                        <a href="${(qrWaMeLink!'#')}" target="_blank" rel="noopener"
                           style="color:#1a73e8; text-decoration:none; font-weight:bold;">
                            ${msg("wa2fa.qr.openWhatsApp","Open in WhatsApp")} &rarr;
                        </a>
                    </p>
                </div>

                <#-- Hidden form field for QR verification -->
                <input type="hidden" id="qrVerifiedInput" name="qrVerified" value="" disabled />
                <input type="hidden" id="activeTabInput" name="activeTab" value="qr" />

                <#-- JavaScript: QR Code generation + Polling (QR-only mode) -->
                <script>
                    (function() {
                        var link = "${(qrWaMeLink!'')?js_string}";
                        if (!link) return;

                        var script = document.createElement('script');
                        script.src = '${url.resourcesPath}/js/qrcode.min.js';
                        script.onload = function() {
                            var qr = qrcode(0, 'M');
                            qr.addData(link);
                            qr.make();

                            var canvas = document.getElementById('qr-canvas');
                            var ctx = canvas.getContext('2d');
                            var moduleCount = qr.getModuleCount();
                            var cellSize = Math.max(4, Math.floor(200 / moduleCount));
                            var size = moduleCount * cellSize;
                            canvas.width = size;
                            canvas.height = size;

                            for (var row = 0; row < moduleCount; row++) {
                                for (var col = 0; col < moduleCount; col++) {
                                    ctx.fillStyle = qr.isDark(row, col) ? '#000000' : '#ffffff';
                                    ctx.fillRect(col * cellSize, row * cellSize, cellSize, cellSize);
                                }
                            }
                        };
                        document.head.appendChild(script);
                    })();

                    (function() {
                        var statusUrl = "${(qrStatusUrl!'')?js_string}";
                        if (!statusUrl) return;

                        var statusDiv = document.getElementById('qr-status');
                        var pollInterval = null;
                        var pollCount = 0;
                        var maxPolls = 60;

                        function showExpired() {
                            statusDiv.style.display = 'block';
                            statusDiv.style.background = '#fff3cd';
                            statusDiv.style.color = '#856404';
                            statusDiv.innerHTML = '${msg("wa2fa.qr.expired","QR code expired. Please refresh.")?js_string}'
                                + '<br/><button type="button" onclick="window.location.reload();" '
                                + 'style="margin-top:10px; padding:8px 24px; border:1px solid #856404; '
                                + 'background:#fff; color:#856404; border-radius:4px; cursor:pointer; font-weight:bold;">'
                                + '${msg("wa2fa.qr.refreshButton","Refresh")?js_string}</button>';
                        }

                        function checkStatus() {
                            pollCount++;
                            if (pollCount > maxPolls) {
                                clearInterval(pollInterval);
                                showExpired();
                                return;
                            }

                            fetch(statusUrl)
                                .then(function(resp) { return resp.json(); })
                                .then(function(data) {
                                    if (data.status === 'verified') {
                                        clearInterval(pollInterval);
                                        statusDiv.style.display = 'block';
                                        statusDiv.style.background = '#d4edda';
                                        statusDiv.style.color = '#155724';
                                        statusDiv.textContent = '${msg("wa2fa.qr.verified","Phone verified via WhatsApp! Completing...")?js_string}';

                                        var input = document.getElementById('qrVerifiedInput');
                                        input.disabled = false;
                                        input.value = 'true';
                                        document.getElementById('kc-otp-form').submit();
                                    } else if (data.status === 'pending_confirm') {
                                        statusDiv.style.display = 'block';
                                        statusDiv.style.background = '#fff3cd';
                                        statusDiv.style.color = '#856404';
                                        statusDiv.innerHTML = '&#128241; Click the verification link sent to your WhatsApp to confirm your phone number...';
                                    } else if (data.status === 'expired' || data.status === 'not_found') {
                                        clearInterval(pollInterval);
                                        showExpired();
                                    }
                                })
                                .catch(function(err) {
                                    console.error('QR status poll error:', err);
                                });
                        }

                        pollInterval = setInterval(checkStatus, 5000);
                        setTimeout(checkStatus, 2000);
                    })();
                </script>

            <#elseif isQrEnabled && isOtpEnabled>
                <#-- ============================================ -->
                <#-- DUAL MODE: QR Code + OTP tabs                -->
                <#-- ============================================ -->

                <#-- Phone number hint -->
                <#if (phoneLast4!'') != ''>
                    <p style="text-align:center; margin-bottom:1em; color:#555;">
                        ${msg("wa2fa.otp.sentToPhone","Verify your WhatsApp number ending in")} <strong>${phoneLast4}</strong>
                    </p>
                </#if>

                <#-- Method tabs -->
                <div style="display:flex; border-bottom:2px solid #e0e0e0; margin-bottom:1.5em;">
                    <button type="button" id="tab-qr" onclick="switchTab('qr')"
                            style="flex:1; padding:12px; border:none; background:#1a73e8; color:#fff; cursor:pointer; font-weight:bold; border-radius:4px 0 0 0; transition: background 0.2s;">
                        ${msg("wa2fa.qr.tabScanQr","Scan QR Code")}
                    </button>
                    <button type="button" id="tab-otp" onclick="switchTab('otp')"
                            style="flex:1; padding:12px; border:none; background:#f5f5f5; color:#333; cursor:pointer; font-weight:bold; border-radius:0 4px 0 0; transition: background 0.2s;">
                        ${msg("wa2fa.qr.tabReceiveOtp","Enter OTP Code")}
                    </button>
                </div>

                <#-- QR Code Method -->
                <div id="method-qr">
                    <div style="text-align:center; padding:1em 0;">
                        <p style="margin-bottom:1em; color:#555;">
                            ${msg("wa2fa.qr.instruction","Scan this QR code with your phone camera. It will open WhatsApp with a pre-filled verification message. Just tap Send.")}
                        </p>

                        <div id="qr-code-container" style="display:inline-block; padding:16px; background:#fff; border:1px solid #e0e0e0; border-radius:8px;">
                            <canvas id="qr-canvas"></canvas>
                        </div>

                        <p style="margin-top:1em; font-size:0.9em; color:#888;">
                            ${msg("wa2fa.qr.tokenLabel","Verification Code")}: <strong>${(qrToken!'')}</strong>
                        </p>

                        <#-- Status indicator -->
                        <div id="qr-status" style="margin-top:1em; padding:10px; border-radius:4px; display:none;">
                        </div>

                        <#-- Mobile fallback: direct link -->
                        <p style="margin-top:1em;">
                            <a href="${(qrWaMeLink!'#')}" target="_blank" rel="noopener"
                               style="color:#1a73e8; text-decoration:none; font-weight:bold;">
                                ${msg("wa2fa.qr.openWhatsApp","Open in WhatsApp")} &rarr;
                            </a>
                        </p>
                    </div>

                    <#-- Hidden form field for QR verification -->
                    <input type="hidden" id="qrVerifiedInput" name="qrVerified" value="" disabled />
                    <#-- Hidden field to preserve active tab across page refresh -->
                    <input type="hidden" id="activeTabInput" name="activeTab" value="${(activeTab!'qr')}" />
                </div>

                <#-- OTP Method -->
                <div id="method-otp" style="display:none;">
                    <#if (otpSent!false)>
                        <#-- OTP already sent — show input -->
                        <div class="${properties.kcFormGroupClass!}">
                            <div class="${properties.kcLabelWrapperClass!}">
                                <label for="otp" class="${properties.kcLabelClass!}">
                                    ${msg("wa2fa.otp.label","Verification Code")}
                                </label>
                            </div>
                            <div class="${properties.kcInputWrapperClass!}">
                                <input type="text" id="otp" name="otp"
                                       class="${properties.kcInputClass!}"
                                       autocomplete="off"
                                       pattern="[0-9]*" inputmode="numeric" maxlength="10"
                                       placeholder="${msg("wa2fa.otp.placeholder","Enter code")}"
                                       aria-invalid="<#if messagesPerField.existsError('otp')>true</#if>" />
                                <#if messagesPerField.existsError('otp')>
                                    <span id="input-error-otp" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                                        ${kcSanitize(messagesPerField.get('otp'))?no_esc}
                                    </span>
                                </#if>
                            </div>
                        </div>

                        <div class="${properties.kcFormGroupClass!}">
                            <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                                       type="submit"
                                       value="${msg("wa2fa.otp.submit","Verify")}" />
                            </div>
                        </div>

                        <div class="${properties.kcFormGroupClass!}" style="text-align:center; margin-top:1em;">
                            <button type="submit" name="resend" value="true"
                                    class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}">
                                ${msg("wa2fa.otp.resend","Resend Code")}
                            </button>
                        </div>
                    <#else>
                        <#-- OTP not yet sent — show "Send OTP" button -->
                        <div style="text-align:center; padding:2em 0;">
                            <p style="margin-bottom:1.5em; color:#555;">
                                ${msg("wa2fa.otp.clickToSend","Click below to receive a verification code via WhatsApp.")}
                            </p>
                            <button type="submit" name="sendOtpTab" value="true"
                                    class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}">
                                ${msg("wa2fa.otp.sendOtpButton","Send OTP Code")}
                            </button>
                        </div>
                    </#if>
                </div>

                <#-- JavaScript: QR Code generation + Polling + Tab switching -->
                <script>
                    function switchTab(tab) {
                        var qrDiv = document.getElementById('method-qr');
                        var otpDiv = document.getElementById('method-otp');
                        var tabQr = document.getElementById('tab-qr');
                        var tabOtp = document.getElementById('tab-otp');
                        var activeTabInput = document.getElementById('activeTabInput');

                        if (tab === 'qr') {
                            qrDiv.style.display = 'block';
                            otpDiv.style.display = 'none';
                            tabQr.style.background = '#1a73e8';
                            tabQr.style.color = '#fff';
                            tabOtp.style.background = '#f5f5f5';
                            tabOtp.style.color = '#333';
                        } else {
                            qrDiv.style.display = 'none';
                            otpDiv.style.display = 'block';
                            tabOtp.style.background = '#1a73e8';
                            tabOtp.style.color = '#fff';
                            tabQr.style.background = '#f5f5f5';
                            tabQr.style.color = '#333';
                            var otpInput = document.getElementById('otp');
                            if (otpInput) otpInput.focus();
                        }
                        // Persist active tab so server can restore it on page refresh
                        if (activeTabInput) activeTabInput.value = tab;
                    }

                    (function() {
                        var link = "${(qrWaMeLink!'')?js_string}";
                        if (!link) return;

                        var script = document.createElement('script');
                        script.src = '${url.resourcesPath}/js/qrcode.min.js';
                        script.onload = function() {
                            var qr = qrcode(0, 'M');
                            qr.addData(link);
                            qr.make();

                            var canvas = document.getElementById('qr-canvas');
                            var ctx = canvas.getContext('2d');
                            var moduleCount = qr.getModuleCount();
                            var cellSize = Math.max(4, Math.floor(200 / moduleCount));
                            var size = moduleCount * cellSize;
                            canvas.width = size;
                            canvas.height = size;

                            for (var row = 0; row < moduleCount; row++) {
                                for (var col = 0; col < moduleCount; col++) {
                                    ctx.fillStyle = qr.isDark(row, col) ? '#000000' : '#ffffff';
                                    ctx.fillRect(col * cellSize, row * cellSize, cellSize, cellSize);
                                }
                            }
                        };
                        document.head.appendChild(script);
                    })();

                    (function() {
                        var statusUrl = "${(qrStatusUrl!'')?js_string}";
                        if (!statusUrl) return;

                        var statusDiv = document.getElementById('qr-status');
                        var pollInterval = null;
                        var pollCount = 0;
                        var maxPolls = 60;

                        function showExpired() {
                            statusDiv.style.display = 'block';
                            statusDiv.style.background = '#fff3cd';
                            statusDiv.style.color = '#856404';
                            statusDiv.innerHTML = '${msg("wa2fa.qr.expired","QR code expired. Please refresh.")?js_string}'
                                + '<br/><button type="button" onclick="window.location.reload();" '
                                + 'style="margin-top:10px; padding:8px 24px; border:1px solid #856404; '
                                + 'background:#fff; color:#856404; border-radius:4px; cursor:pointer; font-weight:bold;">'
                                + '${msg("wa2fa.qr.refreshButton","Refresh")?js_string}</button>';
                        }

                        function checkStatus() {
                            pollCount++;
                            if (pollCount > maxPolls) {
                                clearInterval(pollInterval);
                                showExpired();
                                return;
                            }

                            fetch(statusUrl)
                                .then(function(resp) { return resp.json(); })
                                .then(function(data) {
                                    if (data.status === 'verified') {
                                        clearInterval(pollInterval);
                                        statusDiv.style.display = 'block';
                                        statusDiv.style.background = '#d4edda';
                                        statusDiv.style.color = '#155724';
                                        statusDiv.textContent = '${msg("wa2fa.qr.verified","Phone verified via WhatsApp! Completing...")?js_string}';

                                        var input = document.getElementById('qrVerifiedInput');
                                        input.disabled = false;
                                        input.value = 'true';
                                        document.getElementById('kc-otp-form').submit();
                                    } else if (data.status === 'pending_confirm') {
                                        statusDiv.style.display = 'block';
                                        statusDiv.style.background = '#fff3cd';
                                        statusDiv.style.color = '#856404';
                                        statusDiv.innerHTML = '&#128241; Click the verification link sent to your WhatsApp to confirm your phone number...';
                                    } else if (data.status === 'expired' || data.status === 'not_found') {
                                        clearInterval(pollInterval);
                                        showExpired();
                                    }
                                })
                                .catch(function(err) {
                                    console.error('QR status poll error:', err);
                                });
                        }

                        pollInterval = setInterval(checkStatus, 5000);
                        setTimeout(checkStatus, 2000);
                    })();

                    // Restore active tab from server-side attribute (persists across refresh)
                    (function() {
                        var savedTab = '${(activeTab!"qr")?js_string}';
                        if (savedTab === 'otp') {
                            switchTab('otp');
                        }
                        // else: QR tab is already default/active
                    })();
                </script>

            <#elseif isOtpEnabled>
                <#-- ============================================ -->
                <#-- OTP-ONLY MODE (original flow, no QR)         -->
                <#-- ============================================ -->

                <#-- Phone number hint -->
                <#if (phoneLast4!'') != ''>
                    <p style="text-align:center; margin-bottom:1em; color:#555;">
                        ${msg("wa2fa.otp.sentToPhone","Verify your WhatsApp number ending in")} <strong>${phoneLast4}</strong>
                    </p>
                </#if>

                <#if (otpSent!false)>
                    <div class="${properties.kcFormGroupClass!}">
                        <div class="${properties.kcLabelWrapperClass!}">
                            <label for="otp" class="${properties.kcLabelClass!}">
                                ${msg("wa2fa.otp.label","Verification Code")}
                            </label>
                        </div>
                        <div class="${properties.kcInputWrapperClass!}">
                            <input type="text" id="otp" name="otp"
                                   class="${properties.kcInputClass!}"
                                   autofocus autocomplete="off"
                                   pattern="[0-9]*" inputmode="numeric" maxlength="10"
                                   placeholder="${msg("wa2fa.otp.placeholder","Enter code")}"
                                   aria-invalid="<#if messagesPerField.existsError('otp')>true</#if>" />
                            <#if messagesPerField.existsError('otp')>
                                <span id="input-error-otp" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                                    ${kcSanitize(messagesPerField.get('otp'))?no_esc}
                                </span>
                            </#if>
                        </div>
                    </div>

                    <div class="${properties.kcFormGroupClass!}">
                        <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                            <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                                   type="submit"
                                   value="${msg("wa2fa.otp.submit","Verify")}" />
                        </div>
                    </div>

                    <div class="${properties.kcFormGroupClass!}" style="text-align:center; margin-top:1em;">
                        <button type="submit" name="resend" value="true"
                                class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}">
                            ${msg("wa2fa.otp.resend","Resend Code")}
                        </button>
                    </div>
                <#else>
                    <#-- OTP not yet sent (e.g. QR was configured but failed to render) -->
                    <div style="text-align:center; padding:2em 0;">
                        <p style="margin-bottom:1.5em; color:#555;">
                            ${msg("wa2fa.otp.clickToSend","Click below to receive a verification code via WhatsApp.")}
                        </p>
                        <button type="submit" name="sendOtpTab" value="true"
                                class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}">
                            ${msg("wa2fa.otp.sendOtpButton","Send OTP Code")}
                        </button>
                    </div>
                </#if>
            </#if>
        </form>
    <#elseif section = "info">
        <#assign isQrEnabled = (qrEnabled!false) && (qrWaMeLink??)>
        <#assign isOtpEnabled = (otpEnabled!true)>
        <#if isQrEnabled && !isOtpEnabled>
            <p>${msg("wa2fa.qr.helpTextQrOnly","Scan the QR code to verify via WhatsApp.")}</p>
        <#elseif isQrEnabled && isOtpEnabled>
            <p>${msg("wa2fa.qr.helpText","Scan the QR code to verify via WhatsApp, or switch to the OTP tab to enter a code manually.")}</p>
        <#else>
            <p>${msg("wa2fa.otp.instruction","A verification code has been sent to your WhatsApp. Enter it above.")}</p>
        </#if>
    </#if>
</@layout.registrationLayout>
