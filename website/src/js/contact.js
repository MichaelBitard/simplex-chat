(function () {

  let complete = false
  run()
  window.onload = run

  async function run() {
    const connURIel = document.getElementById("conn_req_uri_text");
    const mobileConnURIanchor = document.getElementById("mobile_conn_req_uri");
    const connQRCodes = document.getElementsByClassName("conn_req_uri_qrcode");
    console.log(connQRCodes);
    if (complete || !connURIel || !mobileConnURIanchor || connQRCodes < 2) return
    complete = true
    const connURI = document.location.toString().replace(/\/(contact|invitation)\//, "/$1");
    connURIel.innerText = "/c " + connURI;
    const parsedURI = new URL(connURI)
    mobileConnURIanchor.href = "simplex:" + parsedURI.pathname + parsedURI.hash
    // const els = document.querySelectorAll(".content_copy_with_tooltip");
    // if (navigator.clipboard) {
    //   els.forEach(contentCopyWithTooltip)
    // } else {
    //   const tooltips = document.querySelectorAll(".content_copy_with_tooltip .tooltip");
    //   tooltips.forEach(el => el.style.visibility = "hidden")
    // }
    
    for (const connQRCode of connQRCodes) {
      try {
        await QRCode.toCanvas(connQRCode, connURI, {
          errorCorrectionLevel: "M",
          color: { dark: "#062D56" }
        });
        connQRCode.style.width = "320px";
        connQRCode.style.height = "320px";
      } catch (err) {
        console.error(err);
      }
    }

    function contentCopyWithTooltip(parent) {
      const content = parent.querySelector(".content");
      const tooltip = parent.querySelector(".tooltiptext");
      console.log(parent.querySelector(".content_copy"), 111)
      console.log(parent)
      const copyButton = parent.querySelector(".content_copy");
      copyButton.addEventListener("click", copyAddress)
      copyButton.addEventListener("mouseout", resetTooltip)

      function copyAddress() {
        navigator.clipboard.writeText(content.innerText || content.value);
        tooltip.innerHTML = "Copied!";
      }

      function resetTooltip() {
        tooltip.innerHTML = "Copy to clipboard";
      }
    }

    function copyAddress() {
      navigator.clipboard.writeText(connURI);
      tooltipEl.innerHTML = "Copied!";
    }

    function resetTooltip() {
      tooltipEl.innerHTML = "Copy to clipboard";
    }
  }
})();
