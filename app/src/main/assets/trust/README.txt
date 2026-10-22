Root certificates trusted when verifying embedded PDF signatures.

Every file here is a self-signed root published by the Controller of Certifying
Authorities (CCA), Government of India, the root of India's PKI hierarchy. They
are what an e-Aadhaar, PAN, DigiLocker or any other Indian government PDF
ultimately chains to, and bundling them is what turns Adobe's yellow "?" into a
green tick inside Scanly.

Downloaded 26 August 2026 from https://www.cca.gov.in/root_certificate.html
and re-wrapped as PEM without altering a byte of the certificate itself.

  cca-india-2002.pem       CN=CCA India            2002-07-05 .. 2009-07-04
  cca-india-2007.pem       CN=CCA India 2007       2007-06-13 .. 2015-07-04
  cca-india-2011.pem       CN=CCA India 2011       2011-03-11 .. 2016-03-11
  cca-india-2014.pem       CN=CCA India 2014       2014-03-05 .. 2024-03-05
  cca-india-2015-spl.pem   CN=CCA India 2015 SPL   2015-01-29 .. 2025-01-29
  cca-india-2022.pem       CN=CCA India 2022       2022-02-02 .. 2042-02-02
  cca-india-2022-spl.pem   CN=CCA India 2022 SPL   2022-09-20 .. 2042-09-20

The expired roots are kept on purpose. A signature is validated at the moment it
was made, not at the moment it is read, so a 2019 document still has to reach the
root that was current in 2019.

Adding an anchor here is all it takes to trust another country's PKI: drop the
PEM or DER root in this folder. SignatureValidator loads every file it finds.
