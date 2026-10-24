# Embedded Termux runtime

The embedded runtime is built from the Termux bootstrap produced by:

- https://github.com/termux/termux-packages

It includes Termux packages and a Python runtime. Those components retain
their respective upstream licenses, including the GNU General Public License
where applicable. The app uses the bootstrap as an embedded Android/bionic
userland and does not require the Termux app to be installed.
